package com.streeter.ui.streetdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.domain.model.StreetWalkEntry
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
import com.streeter.ui.map.fitBoundsToJson
import org.maplibre.android.maps.MapLibreMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: StreetDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    // Fit map bounds when geometry loads or walk selection changes.
    // On walk selection, fit to that walk; otherwise show all walks combined.
    val geometryForBounds = uiState.selectedWalkGeometryJson ?: uiState.combinedGeometryJson
    LaunchedEffect(mapRef, geometryForBounds) {
        val map = mapRef ?: return@LaunchedEffect
        val json = geometryForBounds ?: return@LaunchedEffect
        fitBoundsToJson(map, json)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.streetName.ifBlank { "Street Detail" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.notFound -> Text("Street not found", modifier = Modifier.align(Alignment.Center))
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            MapLibreMapView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(28.dp)),
                                styleUrl = MAP_STYLE_URL,
                                routeGeometryJson = uiState.combinedGeometryJson,
                                previewGeometryJson = uiState.selectedWalkGeometryJson,
                                onMapReady = { map ->
                                    map.uiSettings.isScrollGesturesEnabled = true
                                    map.uiSettings.isZoomGesturesEnabled = true
                                    map.uiSettings.isTiltGesturesEnabled = false
                                    mapRef = map
                                }
                            )
                        }

                        item {
                            Spacer(Modifier.height(16.dp))
                            StreetStatRow(
                                totalLengthM = uiState.totalLengthM,
                                coveredLengthM = uiState.coveredLengthM,
                                coveredPct = uiState.coveredPct
                            )
                        }

                        if (uiState.walks.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(24.dp))
                                WalksOnStreetHeader(count = uiState.walks.size)
                                Spacer(Modifier.height(12.dp))
                                WalksOnStreetCard(
                                    walks = uiState.walks,
                                    selectedWalkId = uiState.selectedWalkId,
                                    onWalkClick = viewModel::selectWalk
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreetStatRow(
    totalLengthM: Double,
    coveredLengthM: Double,
    coveredPct: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StreetStatCard(
            value = formatDistance(totalLengthM),
            label = "Total Length",
            modifier = Modifier.weight(1f)
        )
        StreetStatCard(
            value = formatDistance(coveredLengthM),
            label = "Covered",
            modifier = Modifier.weight(1f)
        )
        StreetStatCard(
            value = "${(coveredPct * 100).roundToInt()}%",
            label = "Coverage",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StreetStatCard(value: String, label: String, modifier: Modifier = Modifier) {
    val numericPart = value.takeWhile { it.isDigit() || it == '.' }
    val unitPart = value.drop(numericPart.length).trim()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = numericPart,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.8).sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unitPart.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = unitPart,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = label.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WalksOnStreetHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = "Walks on this street",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "$count total",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WalksOnStreetCard(
    walks: List<StreetWalkEntry>,
    selectedWalkId: Long?,
    onWalkClick: (Long) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(6.dp)
    ) {
        Column {
            walks.forEachIndexed { index, walk ->
                WalkEntryRow(
                    entry = walk,
                    isSelected = walk.walkId == selectedWalkId,
                    onClick = { onWalkClick(walk.walkId) }
                )
                if (index < walks.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkEntryRow(
    entry: StreetWalkEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val dateLabel = remember(entry.walkDate) { dateFormatter.format(Date(entry.walkDate)) }
    val pct = (entry.coveragePct * 100).roundToInt().coerceIn(0, 100)

    val bgModifier = if (isSelected) {
        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
    } else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(bgModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$pct%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatDistance(entry.walkedLengthM)} on this street",
                fontSize = 12.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000)} km"
    else "${meters.roundToInt()} m"
