package com.streeter.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.model.WalkStreetCoverage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Tier colors per design spec — hardcoded, not theme tokens
private val FullColorLight    = Color(0xFF2E7D32)
private val FullBgLight       = Color(0xFFD4EDD6)
private val FullColorDark     = Color(0xFF8ED99A)
private val FullBgDark        = Color(0xFF1F3A23)

private val PartialColorLight = Color(0xFFB26A00)
private val PartialBgLight    = Color(0xFFFFE8C4)
private val PartialColorDark  = Color(0xFFF5C06F)
private val PartialBgDark     = Color(0xFF3A2A14)

private val LowColorLight     = Color(0xFFB71C1C)
private val LowBgLight        = Color(0xFFF9D6D6)
private val LowColorDark      = Color(0xFFEF9A9A)
private val LowBgDark         = Color(0xFF3A1414)

private enum class Tier { Full, Partial, Low }

private fun WalkStreetCoverage.tier() = when {
    coveragePct >= 1f        -> Tier.Full
    coveragePct >= 0.5f      -> Tier.Partial
    else                     -> Tier.Low
}

@Composable
private fun tierColors(tier: Tier, dark: Boolean): Pair<Color, Color> = when (tier) {
    Tier.Full    -> if (dark) FullColorDark    to FullBgDark    else FullColorLight    to FullBgLight
    Tier.Partial -> if (dark) PartialColorDark to PartialBgDark else PartialColorLight to PartialBgLight
    Tier.Low     -> if (dark) LowColorDark     to LowBgDark     else LowColorLight     to LowBgLight
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkDetailScreen(
    onNavigateBack: () -> Unit,
    onEditRoute: (Long) -> Unit,
    viewModel: WalkDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onNavigateBack()
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text("Delete walk?") },
            text = { Text("This walk and all its data will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteWalk() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.label_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val walk = uiState.walk
                    if (walk != null &&
                        walk.status != WalkStatus.RECORDING &&
                        walk.status != WalkStatus.PENDING_MATCH
                    ) {
                        IconButton(onClick = { onEditRoute(walk.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.label_edit_route))
                        }
                    }
                    IconButton(
                        onClick = { viewModel.showDeleteConfirm() },
                        enabled = uiState.walk?.status != WalkStatus.RECORDING
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.label_delete))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.walk == null -> Text("Walk not found", modifier = Modifier.align(Alignment.Center))
                else -> {
                    val walk = uiState.walk!!
                    val dark = isSystemInDarkTheme()
                    val isProcessing = walk.status == WalkStatus.PENDING_MATCH

                    val fullyCovered  = uiState.streetCoverage.filter { it.tier() == Tier.Full }
                    val partlyCovered = uiState.streetCoverage.filter { it.tier() == Tier.Partial }
                    val lowCoverage   = uiState.streetCoverage.filter { it.tier() == Tier.Low }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        item { WalkHeroHeader(walk = walk) }

                        item {
                            Spacer(Modifier.height(16.dp))
                            MapPreviewPlaceholder()
                        }

                        item {
                            Spacer(Modifier.height(12.dp))
                            WalkMetricRow(
                                walk = walk,
                                streetCount = uiState.streetCoverage.size
                            )
                        }

                        item {
                            if (isProcessing) {
                                Spacer(Modifier.height(16.dp))
                                MatchingProgressBanner(
                                    progress = uiState.matchingProgress,
                                    step = uiState.progressStep
                                )
                            } else if (walk.status == WalkStatus.COMPLETED) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(onClick = { viewModel.recalculateRoute() }) {
                                        Text("Recalculate Route")
                                    }
                                }
                            }
                        }

                        if (!isProcessing && uiState.streetCoverage.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(24.dp))
                                StreetsCoveredHeader(total = uiState.streetCoverage.size)
                                Spacer(Modifier.height(12.dp))
                                TierLegendRow(
                                    fullCount = fullyCovered.size,
                                    partialCount = partlyCovered.size,
                                    lowCount = lowCoverage.size,
                                    dark = dark
                                )
                                Spacer(Modifier.height(14.dp))
                                StreetListCard(
                                    streets = uiState.streetCoverage,
                                    dark = dark
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
private fun WalkHeroHeader(walk: com.streeter.domain.model.Walk) {
    val dateFormatter = remember { SimpleDateFormat("EEE · MMM d · h:mm a", Locale.getDefault()) }
    val dateLabel = remember(walk.date) { dateFormatter.format(Date(walk.date)) }
    val title = walk.title ?: dateLabel

    Column(modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
        Text(
            text = dateLabel.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.8).sp,
                lineHeight = 36.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (walk.source == WalkSource.MANUAL) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.label_manual_badge)) })
            }
        }
    }
}

@Composable
private fun MapPreviewPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Route map",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WalkMetricRow(walk: com.streeter.domain.model.Walk, streetCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BigStatCard(value = formatDistance(walk.distanceM), label = "Distance", modifier = Modifier.weight(1f))
        BigStatCard(value = formatDuration(walk.durationMs), label = "Duration", modifier = Modifier.weight(1f))
        BigStatCard(value = "$streetCount", label = "Streets", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BigStatCard(value: String, label: String, modifier: Modifier = Modifier) {
    // Split numeric value from unit suffix for styled rendering
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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.8).sp,
                    lineHeight = 28.sp,
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
private fun MatchingProgressBanner(progress: Int?, step: String?) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.tertiaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = colors.onTertiaryContainer,
                        trackColor = colors.onTertiaryContainer.copy(alpha = 0.2f)
                    )
                    Text(
                        text = step ?: stringResource(R.string.label_processing),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onTertiaryContainer
                    )
                }
                if (progress != null) {
                    Text(
                        text = "$progress%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.4).sp,
                        color = colors.onTertiaryContainer
                    )
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.onTertiaryContainer,
                    trackColor = colors.onTertiaryContainer.copy(alpha = 0.2f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Snapping GPS points to road network",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onTertiaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun StreetsCoveredHeader(total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = "Streets covered",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "$total total",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TierLegendRow(
    fullCount: Int,
    partialCount: Int,
    lowCount: Int,
    dark: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val (fullColor, fullBg)       = tierColors(Tier.Full, dark)
        val (partialColor, partialBg) = tierColors(Tier.Partial, dark)
        val (lowColor, lowBg)         = tierColors(Tier.Low, dark)

        TierPill(label = "Full",    count = fullCount,    color = fullColor,    bg = fullBg,    modifier = Modifier.weight(1f))
        TierPill(label = "Partial", count = partialCount, color = partialColor, bg = partialBg, modifier = Modifier.weight(1f))
        TierPill(label = "Low",     count = lowCount,     color = lowColor,     bg = lowBg,     modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TierPill(
    label: String,
    count: Int,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = label.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = color
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$count",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = color
            )
        }
    }
}

@Composable
private fun StreetListCard(streets: List<WalkStreetCoverage>, dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(6.dp)
    ) {
        Column {
            streets.forEach { street ->
                StreetRow(street = street, dark = dark)
            }
        }
    }
}

@Composable
private fun StreetRow(street: WalkStreetCoverage, dark: Boolean) {
    val tier = street.tier()
    val (color, bg) = tierColors(tier, dark)
    val pct = (street.coveragePct * 100).roundToInt().coerceIn(0, 100)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Circular percentage badge
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$pct",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = color
            )
        }

        // Street name + coverage bar
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = street.streetName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatDistance(street.walkedLengthM),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = street.coveragePct.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000)} km"
    else "${meters.roundToInt()} m"

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
