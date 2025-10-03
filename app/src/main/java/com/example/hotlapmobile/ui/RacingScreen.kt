package com.example.hotlapmobile.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.config.Track // Assuming you have this import
import com.example.hotlapmobile.data.TrackRepo
import com.example.hotlapmobile.util.haversineMeters

@SuppressLint("MissingPermission") // Add annotation to the top-level function
@Composable
fun RacingScreen() {
    val ctx = LocalContext.current

    // Pull the selected track from your existing DataStore-backed repo
    val trackRepo = remember(ctx) { TrackRepo(ctx) }
    val track = trackRepo.current.collectAsStateWithLifecycle(initialValue = null).value

    var lap by remember { mutableStateOf(0) }
    var insideSF by remember { mutableStateOf(false) } // start/finish zone flag
    var lastDist by remember { mutableStateOf<Double?>(null) }
    var lastLat by remember { mutableStateOf<Double?>(null) }
    var lastLon by remember { mutableStateOf<Double?>(null) }
    var gpsTicks by remember { mutableStateOf(0) }

    // These should be near the top
    val MAX_GPS_AGE_MS = 500L
    val MAX_GPS_ACC_M = 25f

    DisposableEffect(track?.name) {
        val fused = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(ctx)

        // seed from last known (if any)
        fun seedFromLastKnown(loc: android.location.Location) {
            val lat = loc.latitude
            val lon = loc.longitude
            lastLat = lat
            lastLon = lon

            track?.let {
                val d = haversineMeters(lat, lon, it.startFinish.lat, it.startFinish.lon)
                lastDist = d
                // Using track.startFinishRadiusM is more accurate than cornerToleranceM
                val inZone = d <= it.startFinishRadiusM
                if (inZone && !insideSF) lap += 1
                insideSF = inZone
            }
        }

        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) seedFromLastKnown(loc)
        }

        // live updates (200 ms like your working code)
        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            200L
        ).setMinUpdateIntervalMillis(200L).build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return

                val accM = loc.accuracy
                val ageMs = ((android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L)
                    .coerceAtLeast(0L)
                val fresh = (accM <= MAX_GPS_ACC_M) && (ageMs <= MAX_GPS_AGE_MS)

                // always show something, even if stale
                lastLat = loc.latitude
                lastLon = loc.longitude

                gpsTicks += 1

                if (!fresh || track == null) return

                val d = haversineMeters(
                    loc.latitude, loc.longitude,
                    track.startFinish.lat, track.startFinish.lon
                )
                lastDist = d

                // Using track.startFinishRadiusM is more accurate here too
                val inZone = d <= track.startFinishRadiusM
                if (inZone && !insideSF) lap += 1   // rising edge
                insideSF = inZone
            }
        }

        fused.requestLocationUpdates(req, callback, ctx.mainLooper)
        onDispose { fused.removeLocationUpdates(callback) }
    }


    // UI
    val distStr = lastDist?.let { String.format("%.1f", it) } ?: "—"
    val latStr = lastLat?.let { String.format("%.6f", it) } ?: "—"
    val lonStr = lastLon?.let { String.format("%.6f", it) } ?: "—"

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Racing Screen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Track: ${track?.name ?: "— (select a track)"}")
            Spacer(Modifier.height(12.dp))
            Text("Lap: $lap", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("In S/F zone: $insideSF")
            Text("Dist to S/F (m): $distStr")
            Text("GPS: $latStr, $lonStr")
            Text("GPS ticks: $gpsTicks")

            Spacer(Modifier.height(16.dp))
            // Indoor test helpers
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    // Simulate entering S/F (rising edge)
                    if (!insideSF) {
                        insideSF = true
                        lap += 1
                    }
                }) { Text("Simulate Enter S/F") }

                Button(onClick = { insideSF = false }) { Text("Simulate Exit S/F") }
            }
        }
    }
}
