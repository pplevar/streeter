package com.streeter.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R
import com.streeter.ui.map.MAP_STYLE_URL
import com.streeter.ui.map.MapLibreMapView
import org.maplibre.android.geometry.LatLng

@Composable
fun RecordingScreen(
    onStopAndNavigate: (Long) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val gpsPoints by viewModel.gpsPoints.collectAsState()
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

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GPS points: ${gpsPoints.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val walkId = viewModel.stopWalk()
                        onStopAndNavigate(walkId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.label_stop_walk))
                }
            }
        }
    }
}
