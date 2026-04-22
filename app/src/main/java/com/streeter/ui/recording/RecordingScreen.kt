package com.streeter.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
import org.maplibre.android.geometry.LatLng

@Composable
fun RecordingScreen(
    onNavigateBack: () -> Unit,
    onStopAndNavigate: (Long) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val gpsPoints by viewModel.gpsPoints.collectAsState()
    val elapsedMs by viewModel.elapsedMs.collectAsState()
    val distanceM by viewModel.distanceM.collectAsState()
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
            loc?.let { LatLng(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MAP_STYLE_URL,
            gpsPoints = gpsPoints,
            followLocation = true,
            showCurrentPosition = true,
            initialLatLng = initialLatLng
        )

        // Back button
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopStart)
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // REC indicator chip
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.error)
            )
            Text(
                text = "REC",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "·",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatElapsed(elapsedMs),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Bottom metrics card
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricBlock(
                    value = formatDistanceValue(distanceM),
                    unit = formatDistanceUnit(distanceM),
                    label = "Distance",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                MetricBlock(
                    value = formatElapsed(elapsedMs),
                    unit = "",
                    label = "Duration",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                MetricBlock(
                    value = "${gpsPoints.size}",
                    unit = "pts",
                    label = "GPS",
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = {
                    val walkId = viewModel.stopWalk()
                    onStopAndNavigate(walkId)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(
                    text = stringResource(R.string.label_stop_walk),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MetricBlock(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.6).sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDistanceValue(meters: Double): String =
    if (meters >= 1000) "%.1f".format(meters / 1000) else "${meters.toInt()}"

private fun formatDistanceUnit(meters: Double): String =
    if (meters >= 1000) "km" else "m"

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0)
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    else
        "%02d:%02d".format(minutes, seconds)
}
