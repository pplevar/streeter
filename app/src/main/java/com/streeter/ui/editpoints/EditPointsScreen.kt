package com.streeter.ui.editpoints

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.domain.model.GpsPoint
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
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
    var sheetExpanded by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val sheetPeekPx = with(density) { SheetPeekHeight.toPx() }

    fun selectAndCenter(point: GpsPoint) {
        viewModel.selectPoint(point.id)
        sheetExpanded = false
        centerOn(mapRef, point, sheetPeekPx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit GPS points") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            MapLibreMapView(
                modifier = Modifier.fillMaxSize(),
                styleUrl = MAP_STYLE_URL,
                gpsPoints = uiState.points,
                selectedPoint = uiState.selectedPoint,
                onMapReady = { mapRef = it },
            )

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(padding)
                        .animateContentSize()
                        .heightIn(max = if (sheetExpanded) SheetExpandedHeight else SheetPeekHeight)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 8.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .clickable { sheetExpanded = !sheetExpanded },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${uiState.points.size} points",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.points, key = { it.id }) { point ->
                        val index = uiState.points.indexOf(point)
                        PointRow(
                            index = index,
                            point = point,
                            selected = uiState.selectedPointId == point.id,
                            onClick = { selectAndCenter(point) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PointRow(
    index: Int,
    point: GpsPoint,
    selected: Boolean,
    onClick: () -> Unit,
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
            "Point #${index + 1} · ±${point.accuracyM.roundToInt()}m",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
