package com.streeter.ui.manual

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val initialLatLng = remember {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return@remember null
        try {
            val lm = context.getSystemService(LocationManager::class.java)
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

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
                initialLatLng = initialLatLng,
                onCameraMove = { mapLatLng ->
                    viewModel.onCameraMove(LatLng(mapLatLng.latitude, mapLatLng.longitude))
                }
            )

            // Centered pin overlay — visible when actively setting a point
            val pinActive = uiState.step == ManualCreateStep.SET_START ||
                    uiState.step == ManualCreateStep.SET_END
            if (pinActive) {
                val pinColor = if (uiState.step == ManualCreateStep.SET_START)
                    Color(0xFF4CAF50) else Color(0xFFF44336)
                Box(
                    modifier = Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer ring
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(pinColor.copy(alpha = 0.2f), CircleShape)
                            .border(2.dp, pinColor, CircleShape)
                    )
                    // Center dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(pinColor, CircleShape)
                    )
                }
            }

            // Instruction banner
            val instruction = when (uiState.step) {
                ManualCreateStep.SET_START -> "Move map to set start point"
                ManualCreateStep.SET_END -> "Move map to set end point"
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
