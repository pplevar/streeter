package com.streeter.ui.recording

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streeter.R

@Composable
fun RecordingScreen(
    onStopAndNavigate: (Long) -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Recording…", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { onStopAndNavigate(0L) }) {
                Text(stringResource(R.string.label_stop_walk))
            }
        }
    }
}
