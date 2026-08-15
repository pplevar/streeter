package com.streeter.ui.editpoints

import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.GpsPoint
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

private val SheetPeekHeight = 96.dp
private val SheetExpandedHeightFraction = 0.55f
private val SheetExpandedHeightMin = 280.dp
private val SheetExpandedHeightMax = 480.dp

/** Height the prev/delete/next pill and its padding claim above the sheet. */
private val PillInset = 72.dp

/** Height the top app bar claims; the status bar inset is added on top of it. */
private val TopBarInset = 64.dp

/** Breathing room kept between a revealed point and the edge of the uncovered map area. */
private val RevealMargin = 24.dp

/**
 * Pans the camera just enough to bring [point] into the uncovered map area, or leaves it
 * alone if it is already there. Zoom is never touched — selection highlights, it does not
 * navigate (ADR 0007).
 */
private fun revealIfHidden(
    map: MapLibreMap,
    point: GpsPoint,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    insets: MapInsets,
    marginPx: Float,
) {
    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
    val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lng))
    val pan =
        panToReveal(
            pointXPx = screen.x,
            pointYPx = screen.y,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            insets = insets,
            marginPx = marginPx,
        ) ?: return
    // Moving the point by (dx, dy) on screen means moving the camera's centre by the opposite.
    val newCentre =
        map.projection.fromScreenLocation(
            PointF(viewportWidthPx / 2f - pan.dxPx, viewportHeightPx / 2f - pan.dyPx),
        )
    map.animateCamera(CameraUpdateFactory.newLatLng(newCentre))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPointsScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditPointsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val sheetExpandedHeight =
        (configuration.screenHeightDp.dp * SheetExpandedHeightFraction)
            .coerceIn(SheetExpandedHeightMin, SheetExpandedHeightMax)
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarInsetPx = with(density) { navBarInset.toPx() }
    val sheetPeekPx = with(density) { SheetPeekHeight.toPx() }
    val sheetExpandedPx = with(density) { sheetExpandedHeight.toPx() }
    val sheetHeightPx = remember { Animatable(sheetExpandedPx) }
    val scope = rememberCoroutineScope()
    val sheetExpanded = sheetHeightPx.value > (sheetPeekPx + sheetExpandedPx) / 2f
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.label_undo)
    val deletedMessage = stringResource(R.string.message_point_deleted)
    val listState = rememberLazyListState()
    var mapSizePx by remember { mutableStateOf(IntSize.Zero) }
    val statusBarInsetPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
    val topInsetPx = statusBarInsetPx + with(density) { TopBarInset.toPx() }
    val pillInsetPx = with(density) { PillInset.toPx() }
    val revealMarginPx = with(density) { RevealMargin.toPx() }

    fun exitEditor() {
        scope.launch {
            viewModel.onExit()
            onNavigateBack()
        }
    }

    BackHandler(onBack = ::exitEditor)

    fun snapSheetTo(expanded: Boolean) {
        scope.launch { sheetHeightPx.animateTo(if (expanded) sheetExpandedPx else sheetPeekPx) }
    }

    /**
     * The single path from "a point was chosen" to "the point is selected and actionable":
     * the sheet drops to peek so the prev/delete/next pill is in reach. Shared by list clicks
     * and map taps, so map-driven deletion runs through the existing delete flow untouched.
     */
    fun selectAndPeek(
        pointId: Long,
        origin: SelectionOrigin,
    ) {
        viewModel.selectPoint(pointId, origin)
        snapSheetTo(expanded = false)
    }

    // One camera rule for every selection, whatever changed it: move only to make a point the
    // user cannot see visible, and never change their zoom.
    LaunchedEffect(uiState.selectedPointId, mapRef, mapSizePx) {
        val map = mapRef ?: return@LaunchedEffect
        val point = uiState.selectedPoint ?: return@LaunchedEffect
        revealIfHidden(
            map = map,
            point = point,
            viewportWidthPx = mapSizePx.width.toFloat(),
            viewportHeightPx = mapSizePx.height.toFloat(),
            insets =
                MapInsets(
                    top = topInsetPx,
                    // The sheet settles at peek on every selection, with the pill above it.
                    bottom = sheetPeekPx + navBarInsetPx + pillInsetPx,
                ),
            marginPx = revealMarginPx,
        )
    }

    // The list follows selections it did not make, and only when the row is genuinely off-view.
    LaunchedEffect(uiState.selectedPointId, uiState.selectionOrigin) {
        if (uiState.selectionOrigin == SelectionOrigin.LIST) return@LaunchedEffect
        val index = uiState.indexOf(uiState.selectedPointId) ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { pendingUndo ->
            val result =
                snackbarHostState.showSnackbar(
                    message = deletedMessage,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete(pendingUndo)
            }
        }
    }

    LaunchedEffect(uiState.minPointsMessage) {
        if (uiState.minPointsMessage) {
            delay(3000)
            viewModel.dismissMinPointsMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_edit_points)) },
                navigationIcon = {
                    IconButton(onClick = ::exitEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            MapLibreMapView(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { mapSizePx = it },
                styleUrl = MAP_STYLE_URL,
                gpsPoints = uiState.points,
                selectedPoint = uiState.selectedPoint,
                showPointDots = true,
                onPointTap = { pointId ->
                    if (pointId == null) {
                        viewModel.clearSelection()
                    } else {
                        selectAndPeek(pointId, SelectionOrigin.MAP)
                    }
                },
                onMapReady = { mapRef = it },
            )

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (uiState.selectedPoint != null && !sheetExpanded) {
                    PointControlPill(
                        canGoPrevious = uiState.canGoPrevious,
                        canGoNext = uiState.canGoNext,
                        canDelete = uiState.canDeleteMore,
                        onPrevious = viewModel::selectPrevious,
                        onNext = viewModel::selectNext,
                        onDelete = { uiState.selectedPoint?.let(viewModel::deletePoint) },
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // The sheet paints under the navigation bar: its height is the
                            // content height plus the inset, so the bar sits on the sheet's
                            // own surface rather than on live map content.
                            .height(with(density) { sheetHeightPx.value.toDp() } + navBarInset)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = 8.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(48.dp)
                            .clickable { snapSheetTo(expanded = !sheetExpanded) }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        val expanded = sheetHeightPx.value > (sheetPeekPx + sheetExpandedPx) / 2f
                                        snapSheetTo(expanded)
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        sheetHeightPx.snapTo(
                                            (sheetHeightPx.value - dragAmount).coerceIn(sheetPeekPx, sheetExpandedPx),
                                        )
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.label_points_count, uiState.points.size),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                    if (uiState.minPointsMessage) {
                        Text(
                            stringResource(R.string.message_min_points_floor),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding =
                            PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 2.dp,
                                bottom = 2.dp + navBarInset,
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(uiState.points, key = { it.id }) { point ->
                            val index = uiState.points.indexOf(point)
                            PointRow(
                                index = index,
                                point = point,
                                selected = uiState.selectedPointId == point.id,
                                canDelete = uiState.canDeleteMore,
                                onClick = { selectAndPeek(point.id, SelectionOrigin.LIST) },
                                onDelete = { viewModel.deletePoint(point) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointControlPill(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    canDelete: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.label_previous_point),
            )
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.label_delete),
                tint = if (canDelete) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
        }
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.label_next_point),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointRow(
    index: Int,
    point: GpsPoint,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    canDelete
                } else {
                    false
                }
            },
        )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.label_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                stringResource(R.string.label_point_row, index + 1, point.accuracyM.roundToInt()),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
