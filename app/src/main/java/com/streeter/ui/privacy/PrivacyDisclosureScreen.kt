package com.streeter.ui.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyDisclosureScreen(onAccept: () -> Unit) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Before you start", style = MaterialTheme.typography.headlineMedium)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrivacySection(
                    title = "Your data stays on your device",
                    body = "Streeter never sends your location or route data to any server. " +
                            "All GPS recording, map-matching, and street coverage computation " +
                            "happens entirely on your device."
                )
                PrivacySection(
                    title = "Background location",
                    body = "Streeter uses background location permission to continue recording " +
                            "your route when the screen is off or the app is in the background. " +
                            "Location is only recorded when you explicitly start a walk."
                )
                PrivacySection(
                    title = "What is collected",
                    body = "While recording a walk: GPS coordinates, timestamps, and accuracy. " +
                            "This data is stored locally and used to compute which streets you've walked. " +
                            "You can delete any walk at any time."
                )
                PrivacySection(
                    title = "Exporting data",
                    body = "The only way data leaves your device is if you explicitly choose " +
                            "\"Export all data\" in Settings. This writes a JSON file to your " +
                            "Downloads folder — nothing is sent automatically."
                )
            }

            Button(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            ) {
                Text("Got it — let's walk")
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
