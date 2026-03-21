package com.streeter.ui.manual

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.domain.model.LatLng
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCreateScreen(
    onNavigateBack: () -> Unit,
    onWalkCreated: (Long) -> Unit,
    viewModel: ManualCreateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate to detail/edit when walk is created
    LaunchedEffect(uiState.createdWalkId) {
        uiState.createdWalkId?.let { walkId -> onWalkCreated(walkId) }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Manual Walk") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ManualCreateBottomBar(
                uiState = uiState,
                onSetStartMode = { viewModel.setStep(ManualCreateStep.SET_START) },
                onSetEndMode = { viewModel.setStep(ManualCreateStep.SET_END) },
                onGenerate = { viewModel.generateRoute() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            MapLibreMapView(
                modifier = Modifier.fillMaxSize(),
                styleUrl = MAP_STYLE_URL,
                gpsPoints = emptyList(),
                onMapClick = { latLng ->
                    viewModel.onMapTap(LatLng(latLng.latitude, latLng.longitude))
                }
            )

            // Active mode indicator
            val instruction = when (uiState.step) {
                ManualCreateStep.SET_START -> "Tap map to place start point"
                ManualCreateStep.SET_END -> "Tap map to place end point"
                ManualCreateStep.GENERATING -> "Generating route…"
                ManualCreateStep.DONE -> null
            }
            if (instruction != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = instruction,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (uiState.step == ManualCreateStep.GENERATING) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Generating route…")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualCreateBottomBar(
    uiState: ManualCreateUiState,
    onSetStartMode: () -> Unit,
    onSetEndMode: () -> Unit,
    onGenerate: () -> Unit
) {
    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start pin toggle
            FilterChip(
                selected = uiState.step == ManualCreateStep.SET_START,
                onClick = onSetStartMode,
                label = {
                    Text(
                        if (uiState.startPin != null) "Start: set" else "Set Start"
                    )
                },
                enabled = uiState.step != ManualCreateStep.GENERATING,
                modifier = Modifier.weight(1f)
            )

            // End pin toggle
            FilterChip(
                selected = uiState.step == ManualCreateStep.SET_END,
                onClick = onSetEndMode,
                label = {
                    Text(
                        if (uiState.endPin != null) "End: set" else "Set End"
                    )
                },
                enabled = uiState.step != ManualCreateStep.GENERATING,
                modifier = Modifier.weight(1f)
            )

            // Generate FAB
            Button(
                onClick = onGenerate,
                enabled = uiState.startPin != null && uiState.endPin != null
                        && uiState.step != ManualCreateStep.GENERATING,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.label_generate_route))
            }
        }
    }
}
