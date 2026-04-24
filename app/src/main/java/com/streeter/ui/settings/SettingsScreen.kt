package com.streeter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
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
                    onClick = { viewModel.dismissClearDataConfirm() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearDataConfirm() }) {
                    Text(stringResource(R.string.label_cancel))
                }
            },
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
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            // GPS Recording
            SettingsSectionHeader("GPS Recording")

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingSlider(
                    label = "GPS sample interval",
                    value = uiState.gpsIntervalSeconds.toFloat(),
                    valueRange = 5f..60f,
                    steps = 10,
                    displayValue = "${uiState.gpsIntervalSeconds} s",
                    onValueChangeFinished = { viewModel.setGpsInterval(it.toInt()) },
                )
                SettingSlider(
                    label = "Max speed filter",
                    value = uiState.maxSpeedKmh.toFloat(),
                    valueRange = 20f..100f,
                    steps = 15,
                    displayValue = "${uiState.maxSpeedKmh} km/h",
                    onValueChangeFinished = { viewModel.setMaxSpeed(it.toInt()) },
                )
            }

            // Map Data
            SettingsSectionHeader("Map Data")

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Refresh map data",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Re-index OSM street data from bundled assets",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
                if (uiState.isRefreshingMapData) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    FilledTonalButton(
                        onClick = { viewModel.refreshMapData() },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                    ) {
                        Text("Refresh", fontSize = 13.sp)
                    }
                }
            }

            // Data
            SettingsSectionHeader("Data")

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clear all data",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Permanently delete all walks and coverage",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        lineHeight = 18.sp,
                    )
                }
                Button(
                    onClick = { viewModel.showClearDataConfirm() },
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("Clear", fontSize = 13.sp)
                }
            }

            // Privacy footer
            Spacer(Modifier.height(28.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = "On-device only",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "GPS, route matching, and coverage run locally. Nothing is sent to any server.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp, start = 6.dp, end = 6.dp),
    )
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChangeFinished: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayValue,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
