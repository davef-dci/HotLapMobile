package com.example.hotlapmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.data.SettingsRepo
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Repo that reads & writes the global settings
    val settingsRepo = remember(context) { SettingsRepo(context) }

    // Observe current saved values (or defaults if first run)
    val globalSettings by settingsRepo.settings
        .collectAsStateWithLifecycle(
            initialValue = com.example.hotlapmobile.config.GlobalSettingsDefaults.default
        )

    // Local text fields (as strings) so the user can edit
    var cornerTolText  by rememberSaveable { mutableStateOf("") }
    var brakeZoneText  by rememberSaveable { mutableStateOf("") }
    var brakeWarnText  by rememberSaveable { mutableStateOf("") }

    // for GG plot
    var ggMaxAbsGText  by rememberSaveable { mutableStateOf("") }
    var ggTrailSecText by rememberSaveable { mutableStateOf("") }

// Whenever globalSettings changes (on save or when screen re-enters),
// update the text fields to reflect the latest active values.
    LaunchedEffect(globalSettings) {
        cornerTolText = globalSettings.cornerToleranceM.toString()
        brakeZoneText = globalSettings.brakeZoneDistanceM.toString()
        brakeWarnText = globalSettings.brakeWarnDistanceM.toString()
    }


    // simple parse helpers
    fun toDoubleOr(old: Double, txt: String): Double {
        return txt.toDoubleOrNull() ?: old
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            Text(
                "Global Track Tuning",
                style = MaterialTheme.typography.titleMedium
            )

            // Corner tolerance (meters)
            OutlinedTextField(
                value = cornerTolText,
                onValueChange = { cornerTolText = it },
                label = { Text("Corner tolerance (m)") },
                supportingText = { Text("How close GPS must be to 'count' as being at a corner") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            // Brake zone distance (meters)
            OutlinedTextField(
                value = brakeZoneText,
                onValueChange = { brakeZoneText = it },
                label = { Text("Brake zone distance (m)") },
                supportingText = { Text("Start capturing a brake point when within this distance of the next corner") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            // Brake warn distance (meters)
            OutlinedTextField(
                value = brakeWarnText,
                onValueChange = { brakeWarnText = it },
                label = { Text("Brake warn distance (m)") },
                supportingText = { Text("How far out to start the on-screen brake marker/countdown") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Divider()
            Text("G-G Plot", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = ggMaxAbsGText,
                onValueChange = { ggMaxAbsGText = it },
                label = { Text("G-G max scale (G)") },
                supportingText = { Text("Circle radius — typical 1.0–3.0 G") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = ggTrailSecText,
                onValueChange = { ggTrailSecText = it },
                label = { Text("G-G trail window (s)") },
                supportingText = { Text("Points fade out over this duration") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )


            Button(
                onClick = {
                    // When the user taps Save:
                    val newCornerTol   = toDoubleOr(globalSettings.cornerToleranceM, cornerTolText)
                    val newBrakeZone   = toDoubleOr(globalSettings.brakeZoneDistanceM, brakeZoneText)
                    val newBrakeWarn   = toDoubleOr(globalSettings.brakeWarnDistanceM, brakeWarnText)

                    // NEW: parse + clamp to sensible ranges
                    val newGgMaxAbsG  = toDoubleOr(globalSettings.ggMaxAbsG, ggMaxAbsGText).coerceIn(0.5, 5.0)
                    val newGgTrailSec = toDoubleOr(globalSettings.ggTrailSeconds, ggTrailSecText).coerceIn(0.2, 20.0)

                    scope.launch {
                        settingsRepo.updateAll(
                            com.example.hotlapmobile.config.GlobalSettings(
                                cornerToleranceM   = newCornerTol,
                                brakeZoneDistanceM = newBrakeZone,
                                brakeWarnDistanceM = newBrakeWarn,
                                ggMaxAbsG          = newGgMaxAbsG,     // NEW
                                ggTrailSeconds     = newGgTrailSec     // NEW
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            // Show what's currently active (after save this will change because Flow updates)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Active values", style = MaterialTheme.typography.titleSmall)
                    Text("Corner tolerance: ${"%.1f".format(globalSettings.cornerToleranceM)} m")
                    Text("Brake zone distance: ${"%.1f".format(globalSettings.brakeZoneDistanceM)} m")
                    Text("Brake warn distance: ${"%.1f".format(globalSettings.brakeWarnDistanceM)} m")
                }
            }
        }
    }
}
