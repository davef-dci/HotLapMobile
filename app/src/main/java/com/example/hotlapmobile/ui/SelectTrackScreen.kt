package com.example.hotlapmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

import com.example.hotlapmobile.config.Tracks
import com.example.hotlapmobile.data.TrackRepo
import com.example.hotlapmobile.data.SettingsRepo  // ✅ ADD THIS
import androidx.compose.runtime.getValue            // ✅ ADD THIS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTrackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Track repo: which track is currently selected
    val trackRepo = remember(context) { TrackRepo(context) }
    val currentTrack by trackRepo.current
        .collectAsStateWithLifecycle(initialValue = null)

    // Settings repo: global tuning values
    val settingsRepo = remember(context) { SettingsRepo(context) }
    val globalSettings by settingsRepo.settings
        .collectAsStateWithLifecycle(
            initialValue = com.example.hotlapmobile.config.GlobalSettingsDefaults.default
        )

    // Pull individual values out for display
    val cornerToleranceM   = globalSettings.cornerToleranceM
    val brakeZoneDistanceM = globalSettings.brakeZoneDistanceM
    val brakeWarnDistanceM = globalSettings.brakeWarnDistanceM

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select a Race Track") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for (track in Tracks.all) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                trackRepo.save(track)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor =
                            if (currentTrack?.name == track.name)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.titleLarge
                        )

                        Spacer(Modifier.height(4.dp))

                        // Show global tuning and per-track start/finish radius
                        Text("Corner tolerance: ${"%.1f".format(cornerToleranceM)} m")
                        Text("Start/Finish tolerance: ${"%.1f".format(track.startFinishRadiusM)} m")
                        Text("Brake zone distance: ${"%.1f".format(brakeZoneDistanceM)} m")
                        Text("Brake warn distance: ${"%.1f".format(brakeWarnDistanceM)} m")

                        if (currentTrack?.name == track.name) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "✓ Selected",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
