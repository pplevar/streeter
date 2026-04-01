package com.streeter.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.model.WalkStreetCoverage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
                title = { Text("Walk Detail") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val walk = uiState.walk
                    if (walk != null && walk.status != WalkStatus.RECORDING) {
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
                uiState.walk == null -> {
                    Text("Walk not found", modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    val walk = uiState.walk!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            WalkHeaderCard(walk = walk)
                        }

                        if (walk.status == WalkStatus.PENDING_MATCH) {
                            item {
                                ProcessingBanner()
                            }
                        }

                        if (walk.status == WalkStatus.COMPLETED) {
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.recalculateRoute() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Recalculate route")
                                }
                            }
                        }

                        if (uiState.streetCoverage.isNotEmpty()) {
                            item {
                                Text(
                                    "Streets covered",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            val fullyCovered = uiState.streetCoverage.filter { it.coveragePct >= 1f }
                            val partlyCovered = uiState.streetCoverage.filter { it.coveragePct in 0.5f..1f && it.coveragePct < 1f }
                            val lowCoverage = uiState.streetCoverage.filter { it.coveragePct < 0.5f }

                            if (fullyCovered.isNotEmpty()) {
                                item { CoverageTierHeader("100% covered", fullyCovered.size) }
                                items(fullyCovered) { StreetCoverageRow(it) }
                            }
                            if (partlyCovered.isNotEmpty()) {
                                item { CoverageTierHeader("50–99% covered", partlyCovered.size) }
                                items(partlyCovered) { StreetCoverageRow(it) }
                            }
                            if (lowCoverage.isNotEmpty()) {
                                item { CoverageTierHeader("< 50% covered", lowCoverage.size) }
                                items(lowCoverage) { StreetCoverageRow(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WalkHeaderCard(walk: com.streeter.domain.model.Walk) {
    val dateFormatter = remember { SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = walk.title ?: dateFormatter.format(Date(walk.date)),
                style = MaterialTheme.typography.headlineSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (walk.source == WalkSource.MANUAL) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.label_manual_badge)) })
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricColumn("Distance", formatDistance(walk.distanceM))
                MetricColumn("Duration", formatDuration(walk.durationMs))
            }
        }
    }
}

@Composable
private fun ProcessingBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.label_processing))
        }
    }
}

@Composable
private fun CoverageTierHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("$count streets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun StreetCoverageRow(coverage: WalkStreetCoverage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = coverage.streetName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.coverage_pct_format, coverage.coveragePct * 100),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) "${"%.1f".format(meters / 1000)} km"
    else "${meters.roundToInt()} m"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
