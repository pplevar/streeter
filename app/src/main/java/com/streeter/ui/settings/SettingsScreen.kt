package com.streeter.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.refreshMapDataError) {
        uiState.refreshMapDataError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (uiState.showClearDataConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearDataConfirm() },
            title = { Text("Clear all data?") },
            text = { Text("All walks, routes, and coverage data will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.dismissClearDataConfirm() /* TODO: implement clear in Phase 8 */ },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearDataConfirm() }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // GPS Recording section
            SectionHeader("GPS Recording")

            SettingSlider(
                label = "GPS sample interval",
                value = uiState.gpsIntervalSeconds.toFloat(),
                valueRange = 5f..60f,
                steps = 10,
                displayValue = "${uiState.gpsIntervalSeconds} s",
                onValueChangeFinished = { viewModel.setGpsInterval(it.toInt()) }
            )

            SettingSlider(
                label = "Max speed filter threshold",
                value = uiState.maxSpeedKmh.toFloat(),
                valueRange = 20f..100f,
                steps = 15,
                displayValue = "${uiState.maxSpeedKmh} km/h",
                onValueChangeFinished = { viewModel.setMaxSpeed(it.toInt()) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Map Data section
            SectionHeader("Map Data")

            ListItem(
                headlineContent = { Text("Refresh map data") },
                supportingContent = { Text("Re-process OSM graph for street matching") },
                trailingContent = {
                    if (uiState.isRefreshingMapData) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        TextButton(onClick = { viewModel.refreshMapData() }) {
                            Text("Refresh")
                        }
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Data management section
            SectionHeader("Data")

            ListItem(
                headlineContent = { Text("Clear all data") },
                supportingContent = { Text("Delete all walks, routes, and coverage data") },
                trailingContent = {
                    TextButton(
                        onClick = { viewModel.showClearDataConfirm() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Clear") }
                }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
