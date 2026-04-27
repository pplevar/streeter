package com.streeter.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.ui.theme.MapRouteBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onWalkSelected: (Long) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_history)) },
                navigationIcon = {
                    onNavigateBack?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Newest first") },
                                onClick = {
                                    viewModel.setSortOrder(WalkSortOrder.NEWEST)
                                    showSortMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Longest first") },
                                onClick = {
                                    viewModel.setSortOrder(WalkSortOrder.LONGEST)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            StatPillsRow(uiState.weeklyStats)

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    uiState.walks.isEmpty() ->
                        Text(
                            text = stringResource(R.string.label_no_walks),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    else ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(uiState.walks, key = { it.id }) { walk ->
                                WalkCard(
                                    walk = walk,
                                    streetCount = uiState.streetCountByWalkId[walk.id] ?: 0,
                                    onClick = { onWalkSelected(walk.id) },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun StatPillsRow(stats: WeeklyStats) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatPill(
            modifier = Modifier.weight(1f),
            label = "This week",
            value = "${stats.walkCount} ${if (stats.walkCount == 1) "walk" else "walks"}",
            isPrimary = true,
        )
        StatPill(
            modifier = Modifier.weight(1f),
            label = "Distance",
            value = formatDistance(stats.totalDistanceM),
        )
        StatPill(
            modifier = Modifier.weight(1f),
            label = "Streets",
            value = stats.totalStreetsCount.toString(),
        )
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
) {
    val bg =
        if (isPrimary) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val labelColor =
        if (isPrimary) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val valueColor =
        if (isPrimary) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Surface(modifier = modifier, color = bg, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = valueColor,
            )
        }
    }
}

@Composable
private fun WalkCard(
    walk: Walk,
    streetCount: Int,
    onClick: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val isCompleted = walk.status == WalkStatus.COMPLETED || walk.status == WalkStatus.MANUAL_DRAFT
    val isProcessing = walk.status == WalkStatus.PENDING_MATCH

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            MiniMapThumbnail(
                walkId = walk.id,
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp)),
            )

            Spacer(Modifier.width(14.dp))

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(72.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = walk.title ?: dateFormatter.format(Date(walk.date)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (walk.source == WalkSource.MANUAL) StatusChip("MANUAL", isPrimary = true)
                        if (isProcessing) StatusChip("PROCESSING", isPrimary = false)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricItem(
                        icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                        value = formatDistance(walk.distanceM),
                    )
                    MetricItem(
                        icon = Icons.Outlined.Schedule,
                        value = formatDuration(walk.durationMs),
                    )
                    if (isCompleted) {
                        MetricItem(
                            icon = Icons.Outlined.Place,
                            value = streetCount.toString(),
                            tintPrimary = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    isPrimary: Boolean,
) {
    val bg =
        if (!isPrimary) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val fg =
        if (!isPrimary) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = fg,
        )
    }
}

@Composable
private fun MetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    tintPrimary: Boolean = false,
) {
    val color =
        if (tintPrimary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun MiniMapThumbnail(
    walkId: Long,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)

    Canvas(modifier = modifier.background(bgColor)) {
        val w = size.width
        val h = size.height
        val gridStroke = 0.5.dp.toPx()
        val steps = 4
        repeat(steps - 1) { i ->
            val x = w * (i + 1) / steps
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), gridStroke)
        }
        repeat(steps - 1) { i ->
            val y = h * (i + 1) / steps
            drawLine(gridColor, Offset(0f, y), Offset(w, y), gridStroke)
        }

        val path = Path()

        when (walkId % 4) {
            0L -> {
                path.moveTo(w * 0.20f, h * 0.75f)
                path.lineTo(w * 0.20f, h * 0.45f)
                path.lineTo(w * 0.55f, h * 0.45f)
                path.lineTo(w * 0.55f, h * 0.20f)
                path.lineTo(w * 0.80f, h * 0.20f)
            }
            1L -> {
                path.moveTo(w * 0.15f, h * 0.80f)
                path.lineTo(w * 0.45f, h * 0.80f)
                path.lineTo(w * 0.45f, h * 0.50f)
                path.lineTo(w * 0.75f, h * 0.50f)
                path.lineTo(w * 0.75f, h * 0.25f)
            }
            2L -> {
                path.moveTo(w * 0.20f, h * 0.70f)
                path.lineTo(w * 0.40f, h * 0.70f)
                path.lineTo(w * 0.40f, h * 0.35f)
                path.lineTo(w * 0.65f, h * 0.35f)
                path.lineTo(w * 0.65f, h * 0.15f)
                path.lineTo(w * 0.85f, h * 0.15f)
            }
            else -> {
                path.moveTo(w * 0.15f, h * 0.85f)
                path.lineTo(w * 0.35f, h * 0.85f)
                path.lineTo(w * 0.35f, h * 0.60f)
                path.lineTo(w * 0.60f, h * 0.60f)
                path.lineTo(w * 0.60f, h * 0.35f)
                path.lineTo(w * 0.85f, h * 0.35f)
            }
        }

        drawPath(
            path = path,
            color = MapRouteBlue,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) {
        "${"%.1f".format(meters / 1000)} km"
    } else {
        "${meters.roundToInt()} m"
    }

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "$minutes min"
}
