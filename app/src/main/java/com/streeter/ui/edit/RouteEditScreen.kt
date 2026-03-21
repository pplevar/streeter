package com.streeter.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
fun RouteEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: RouteEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle save completion
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onNavigateBack()
    }

    // Show errors in snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Intercept back gesture when there are unsaved changes
    BackHandler(enabled = uiState.hasUnsavedChanges) {
        showDiscardDialog = true
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard unsaved edits?") },
            text = { Text("Your route corrections will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    viewModel.discardAll()
                    onNavigateBack()
                }) { Text(stringResource(R.string.label_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.correctionCount > 0)
                            "${stringResource(R.string.label_edit_route)} (${uiState.correctionCount})"
                        else
                            stringResource(R.string.label_edit_route)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.hasUnsavedChanges) showDiscardDialog = true
                        else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.correctionCount > 0) {
                        TextButton(
                            onClick = { viewModel.undo() },
                            enabled = uiState.editMode == EditMode.IDLE
                        ) {
                            Text(stringResource(R.string.label_undo))
                        }
                    }
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = uiState.hasUnsavedChanges && uiState.editMode == EditMode.IDLE
                    ) {
                        Text(stringResource(R.string.label_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            EditModeBottomBar(
                uiState = uiState,
                onStartEdit = { viewModel.startSelectAnchor1() },
                onConfirmPreview = { viewModel.confirmPreview() },
                onDiscardPreview = { viewModel.discardPreview() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null && uiState.walk == null -> {
                    Text(
                        text = uiState.errorMessage ?: "Error",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    // Map with route and edit overlay
                    MapLibreMapView(
                        modifier = Modifier.fillMaxSize(),
                        styleUrl = MAP_STYLE_URL,
                        gpsPoints = emptyList(),
                        onMapReady = { /* map ready */ },
                        onMapClick = { latLng ->
                            viewModel.onMapTap(LatLng(latLng.latitude, latLng.longitude))
                        }
                    )

                    // Edit mode instruction overlay
                    EditInstructionOverlay(
                        editMode = uiState.editMode,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    )

                    // Saving overlay
                    if (uiState.editMode == EditMode.SAVING || uiState.editMode == EditMode.RECALCULATING) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Card {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    Text(
                                        if (uiState.editMode == EditMode.SAVING) "Saving…"
                                        else "Recalculating route…"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditModeBottomBar(
    uiState: RouteEditUiState,
    onStartEdit: () -> Unit,
    onConfirmPreview: () -> Unit,
    onDiscardPreview: () -> Unit
) {
    BottomAppBar {
        when (uiState.editMode) {
            EditMode.IDLE -> {
                Button(
                    onClick = onStartEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Correction")
                }
            }
            EditMode.PREVIEW -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDiscardPreview,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.label_discard))
                    }
                    Button(
                        onClick = onConfirmPreview,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.label_confirm))
                    }
                }
            }
            EditMode.SELECT_ANCHOR_1 -> {
                Text(
                    text = "Tap the route to place first anchor",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            EditMode.SELECT_ANCHOR_2 -> {
                Text(
                    text = "Tap the route to place second anchor",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            EditMode.SELECT_WAYPOINT -> {
                Text(
                    text = "Tap the map to place a correction waypoint",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> { /* RECALCULATING / SAVING handled above */ }
        }
    }
}

@Composable
private fun EditInstructionOverlay(editMode: EditMode, modifier: Modifier = Modifier) {
    val text = when (editMode) {
        EditMode.SELECT_ANCHOR_1 -> "Tap route: first anchor"
        EditMode.SELECT_ANCHOR_2 -> "Tap route: second anchor"
        EditMode.SELECT_WAYPOINT -> "Tap map: correction waypoint"
        EditMode.PREVIEW -> "Review the new route segment"
        else -> null
    }
    if (text != null) {
        Card(modifier = modifier) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
