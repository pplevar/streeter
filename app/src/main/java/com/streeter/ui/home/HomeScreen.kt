package com.streeter.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.streeter.R

@Composable
fun HomeScreen(
    onStartWalk: () -> Unit,
    onViewHistory: () -> Unit,
    onCreateManual: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val activeWalkId by viewModel.activeWalkId.collectAsState()
    val cityStats by viewModel.cityStats.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) onStartWalk()
        }

    fun startWalkWithPermissionCheck() {
        val hasLocation =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            onStartWalk()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
        ) {
            // Top bar: app name + settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STREETER",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.label_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Hero tagline
            Text(
                text = "Walk every street in your city.",
                style = MaterialTheme.typography.displaySmall,
            )

            Spacer(Modifier.weight(1f))

            // City progress card
            CityProgressCard(cityStats = cityStats)

            Spacer(Modifier.weight(1f))

            // Primary action button
            val isActiveWalk = activeWalkId != null && activeWalkId!! > 0L
            Button(
                onClick = { startWalkWithPermissionCheck() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                shape = RoundedCornerShape(50),
                colors =
                    if (isActiveWalk) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text =
                        if (isActiveWalk) {
                            stringResource(R.string.label_resume_walk)
                        } else {
                            stringResource(R.string.label_start_walk)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Secondary buttons: History + Manual
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onViewHistory,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.label_history))
                }
                OutlinedButton(
                    onClick = onCreateManual,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manual")
                }
            }
        }
    }
}

@Composable
private fun CityProgressCard(cityStats: CityStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        text = "YOUR CITY",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = "Your City",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${cityStats.coveragePct}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        text = "covered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (cityStats.coveragePct / 100f).coerceIn(0f, 1f) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val statStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                val statColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                val boldSpan = SpanStyle(fontWeight = FontWeight.Bold)

                fun statText(
                    number: String,
                    label: String,
                ) = buildAnnotatedString {
                    withStyle(boldSpan) { append(number) }
                    append(" $label")
                }

                Text(statText("${cityStats.coveredStreets}", "streets"), style = statStyle, color = statColor)
                Text(statText(String.format("%.1f", cityStats.totalDistanceKm), "km walked"), style = statStyle, color = statColor)
                Text(statText("${cityStats.walkCount}", "walks"), style = statStyle, color = statColor)
            }
        }
    }
}
