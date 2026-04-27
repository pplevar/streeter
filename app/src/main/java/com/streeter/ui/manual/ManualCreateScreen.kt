package com.streeter.ui.manual

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCreateScreen(
    onNavigateBack: () -> Unit,
    onWalkCreated: (Long) -> Unit,
    viewModel: ManualCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val initialLatLng =
        remember {
            val moscow = org.maplibre.android.geometry.LatLng(55.7558, 37.6173)
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return@remember moscow
            try {
                val lm = context.getSystemService(LocationManager::class.java)
                val maxAgeMs = 1000L
                val now = System.currentTimeMillis()
                val loc =
                    listOf(
                        "fused",
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                    ).firstNotNullOfOrNull { provider ->
                        lm?.getLastKnownLocation(provider)
                            ?.takeIf { now - it.time < maxAgeMs }
                    }
                loc?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) } ?: moscow
            } catch (_: Exception) {
                moscow
            }
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

    val pinActive =
        uiState.step == ManualCreateStep.PLACING_FIRST_POINT ||
            uiState.step == ManualCreateStep.PLACING_NEXT_POINT
    val isRouting = uiState.step == ManualCreateStep.ROUTING
    val isFinishing = uiState.step == ManualCreateStep.FINISHING

    val instruction =
        when (uiState.step) {
            ManualCreateStep.PLACING_FIRST_POINT -> "Move map to start point, then tap Add Point"
            ManualCreateStep.PLACING_NEXT_POINT -> "Move map to next point, then tap Add Point"
            ManualCreateStep.ROUTING -> "Computing route segment…"
            ManualCreateStep.FINISHING -> "Saving walk…"
            ManualCreateStep.DONE -> null
        }

    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize()) {

        // Full-screen map
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MAP_STYLE_URL,
            gpsPoints = emptyList(),
            initialLatLng = initialLatLng,
            onCameraMove = { mapLatLng ->
                viewModel.onCameraMove(
                    com.streeter.domain.model.LatLng(mapLatLng.latitude, mapLatLng.longitude),
                )
            },
            routeGeometryJson = uiState.accumulatedGeometryJson,
        )

        // Gradient header: solid surface fading to transparent over the app bar area
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to surfaceColor,
                                    0.6f to surfaceColor,
                                    1f to surfaceColor.copy(alpha = 0f),
                                ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(bottom = 24.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    enabled = !isFinishing,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.label_cancel),
                        tint =
                            if (isFinishing) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
                Text(
                    text = stringResource(R.string.label_create_manual),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Instruction banner — pill card below the app bar
        if (instruction != null) {
            Card(
                modifier =
                    Modifier
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                        .align(Alignment.TopCenter),
                shape = RoundedCornerShape(20.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isRouting || isFinishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 0.1.sp,
                    )
                }
            }
        }

        // Points counter pill — top-right, shows count + distance once points are placed
        if (uiState.hasPoints && !isFinishing) {
            Card(
                modifier =
                    Modifier
                        .statusBarsPadding()
                        .padding(top = 120.dp, end = 16.dp)
                        .align(Alignment.TopEnd),
                shape = RoundedCornerShape(14.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${uiState.placedPoints.size} points",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (uiState.totalDistanceM > 0) {
                        Text(
                            text = "· ${formatDistanceKm(uiState.totalDistanceM)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Centered crosshair reticle — green target pin with crosshair arms
        if (pinActive) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(76.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Outer ring with translucent green fill
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(Color(0xFF4CAF50).copy(alpha = 0.18f), CircleShape)
                            .border(2.dp, Color(0xFF4CAF50), CircleShape),
                )
                // White halo behind center dot (simulates CSS box-shadow ring)
                Box(
                    modifier =
                        Modifier
                            .size(18.dp)
                            .background(Color.White.copy(alpha = 0.9f), CircleShape),
                )
                // Green center dot
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                )
                // Left crosshair arm: spans x=[−38,−26] relative to center
                Box(
                    modifier =
                        Modifier
                            .size(12.dp, 2.dp)
                            .offset(x = (-32).dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(1.dp)),
                )
                // Right crosshair arm: spans x=[+26,+38] relative to center
                Box(
                    modifier =
                        Modifier
                            .size(12.dp, 2.dp)
                            .offset(x = 32.dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(1.dp)),
                )
                // Top crosshair arm: spans y=[−38,−26] relative to center
                Box(
                    modifier =
                        Modifier
                            .size(2.dp, 12.dp)
                            .offset(y = (-32).dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(1.dp)),
                )
                // Bottom crosshair arm: spans y=[+26,+38] relative to center
                Box(
                    modifier =
                        Modifier
                            .size(2.dp, 12.dp)
                            .offset(y = 32.dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(1.dp)),
                )
            }
        }

        // Floating bottom bar — rounded card with Undo / Add Point / Finish
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .shadow(6.dp, RoundedCornerShape(32.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        RoundedCornerShape(32.dp),
                    )
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = viewModel::onUndo,
                enabled = uiState.hasPoints && !uiState.isRouting && !isFinishing,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.label_undo),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            Button(
                onClick = viewModel::onConfirmPoint,
                enabled = uiState.currentPin != null && pinActive,
                modifier = Modifier.weight(1.6f).height(48.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.label_add_point),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            FilledTonalButton(
                onClick = viewModel::onFinish,
                enabled = uiState.hasSegments && !uiState.isRouting && !isFinishing,
                modifier = Modifier.weight(1.2f).height(48.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.label_finish_walk),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }

        // Snackbar positioned above the bottom bar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp),
        )

        // Finishing overlay — dark scrim with saving card; rendered last to cover all layers
        if (isFinishing) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = "Saving walk…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDistanceKm(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)
