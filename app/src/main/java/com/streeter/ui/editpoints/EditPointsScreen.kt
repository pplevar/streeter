package com.streeter.ui.editpoints

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.GpsPoint
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

private val SheetPeekHeight = 96.dp
private val SheetExpandedHeight = 420.dp

private fun centerOn(
    map: MapLibreMap?,
    point: GpsPoint,
    bottomPaddingPx: Float,
) {
    map ?: return
    map.animateCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(point.lat, point.lng))
                .zoom(17.5)
                .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble())
                .build(),
        ),
    )
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
    val sheetPeekPx = with(density) { SheetPeekHeight.toPx() }
    val sheetExpandedPx = with(density) { SheetExpandedHeight.toPx() }
    val sheetHeightPx = remember { Animatable(sheetExpandedPx) }
    val scope = rememberCoroutineScope()
    val sheetExpanded = sheetHeightPx.value > (sheetPeekPx + sheetExpandedPx) / 2f
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.label_undo)
    val deletedMessage = stringResource(R.string.message_point_deleted)

    fun snapSheetTo(expanded: Boolean) {
        scope.launch { sheetHeightPx.animateTo(if (expanded) sheetExpandedPx else sheetPeekPx) }
    }

    fun selectAndCenter(point: GpsPoint) {
        viewModel.selectPoint(point.id)
        snapSheetTo(expanded = false)
    }

    LaunchedEffect(uiState.selectedPointId, mapRef) {
        uiState.selectedPoint?.let { centerOn(mapRef, it, sheetPeekPx) }
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
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            MapLibreMapView(
                modifier = Modifier.fillMaxSize(),
                styleUrl = MAP_STYLE_URL,
                gpsPoints = uiState.points,
                selectedPoint = uiState.selectedPoint,
                onMapReady = { mapRef = it },
            )

            if (uiState.selectedPoint != null && !sheetExpanded) {
                PointControlPill(
                    canGoPrevious = uiState.canGoPrevious,
                    canGoNext = uiState.canGoNext,
                    canDelete = uiState.canDeleteMore,
                    onPrevious = viewModel::selectPrevious,
                    onNext = viewModel::selectNext,
                    onDelete = { uiState.selectedPoint?.let(viewModel::deletePoint) },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = SheetPeekHeight + 16.dp),
                )
            }

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(padding)
                        .height(with(density) { sheetHeightPx.value.toDp() })
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 8.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(24.dp)
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
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.points, key = { it.id }) { point ->
                        val index = uiState.points.indexOf(point)
                        PointRow(
                            index = index,
                            point = point,
                            selected = uiState.selectedPointId == point.id,
                            canDelete = uiState.canDeleteMore,
                            onClick = { selectAndCenter(point) },
                            onDelete = { viewModel.deletePoint(point) },
                        )
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
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
