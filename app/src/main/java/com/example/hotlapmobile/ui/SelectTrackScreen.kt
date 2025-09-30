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
import com.example.hotlapmobile.config.Tracks
import com.example.hotlapmobile.data.TrackRepo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTrackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepo(context) }
    val current = repo.current.collectAsStateWithLifecycle(initialValue = null).value
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select a Race Track") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        .clickable { scope.launch { repo.save(track) } },
                    colors = CardDefaults.cardColors(
                        containerColor = if (current?.name == track.name)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(track.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("Corner tolerance: ${track.cornerToleranceM} m")
                        Text("Start/Finish tolerance: ${track.startFinishRadiusM} m")
                        Text("Brake zone distance: ${track.brakeZoneDistanceM} m")
                        Text("Brake warn distance: ${track.brakeWarnDistanceM} m")

                        if (current?.name == track.name) {
                            Spacer(Modifier.height(8.dp))
                            Text("✓ Selected", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
