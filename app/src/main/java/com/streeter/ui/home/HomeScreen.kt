package com.streeter.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streeter.R

@Composable
fun HomeScreen(
    onStartWalk: () -> Unit,
    onViewHistory: () -> Unit,
    onCreateManual: () -> Unit
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
            Text(
                text = "Streeter",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onStartWalk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.label_start_walk))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.label_history))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onCreateManual,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.label_create_manual))
            }
        }
    }
}
