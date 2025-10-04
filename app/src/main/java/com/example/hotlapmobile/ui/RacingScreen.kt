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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.config.Track // Assuming you have this import
import com.example.hotlapmobile.data.TrackRepo
import com.example.hotlapmobile.util.haversineMeters
import kotlinx.coroutines.delay

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

    // GPS timing
    val MAX_GPS_AGE_MS = 500L
    val MAX_GPS_ACC_M = 25f

    // FLags to prevent us from multiple hitting the start/finish
    var armed by remember { mutableStateOf(true) }      // must exit S/F to re-arm
    var lastLapAtMs by remember { mutableStateOf(0L) }

    //variables for lap timing
    var lapStartAtMs by remember { mutableStateOf<Long?>(null) }
    var currentLapMs by remember { mutableStateOf<Long?>(null) }
    var bestLapMs by remember { mutableStateOf<Long?>(null) }

    // Corner detection (Lua parity)
    var atCorner by remember { mutableStateOf(false) }                // Lua: at_corner
    var targetCornerIdx by remember(track) { mutableStateOf(0) }      // Lua: target_corner (0-based; we'll wrap)
    var distToTargetCornerM by remember { mutableStateOf(Double.NaN) } // Lua: distance_to_target_corner


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

                val nowMs = android.os.SystemClock.elapsedRealtime()
                val inZone = d <= track.startFinishRadiusM

// Re-arm when OUTSIDE S/F (keep if you added 'armed' earlier)
                if (!inZone) armed = true

// RISING EDGE + re-arm + 3s lockout (adjust if needed)
                if (inZone && armed && !insideSF && (nowMs - lastLapAtMs) >= 3000L) {
                    // if we were timing a lap, close it and update Best
                    lapStartAtMs?.let { start ->
                        val lapTime = nowMs - start
                        if (bestLapMs == null || lapTime < bestLapMs!!) bestLapMs = lapTime
                    }
                    // start timing the new lap from this crossing
                    lapStartAtMs = nowMs
                    currentLapMs = 0L

                    lap += 1
                    lastLapAtMs = nowMs
                    armed = false
                }
                insideSF = inZone

                // --- Corner logic (Lua parity) ---
                run {
                    val (newAtCorner, newTarget, newDistToTarget) = checkIfAtCornerLua(
                        track = track,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        atCorner = atCorner,
                        targetCornerIdx = targetCornerIdx
                    )
                    atCorner = newAtCorner
                    if (newTarget != targetCornerIdx) targetCornerIdx = newTarget
                    distToTargetCornerM = newDistToTarget
                }


            }
        }

        fused.requestLocationUpdates(req, callback, ctx.mainLooper)
        onDispose { fused.removeLocationUpdates(callback) }
    }


            LaunchedEffect(lapStartAtMs) {
                while (lapStartAtMs != null) {
                    currentLapMs = android.os.SystemClock.elapsedRealtime() - lapStartAtMs!!
                    delay(200L)
                }
            }



    // UI
    val distStr = lastDist?.let { String.format("%.1f", it) } ?: "—"
    val latStr = lastLat?.let { String.format("%.6f", it) } ?: "—"
    val lonStr = lastLon?.let { String.format("%.6f", it) } ?: "—"
    val edgeM  = lastDist?.let { it - (track?.startFinishRadiusM ?: 0.0) }
    val edgeStr = edgeM?.let { String.format("%.1f", it) } ?: "—"
    val showDebug = false

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Racing Screen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Track: ${track?.name ?: "— (select a track)"}")
            Spacer(Modifier.height(12.dp))
            Text("Lap: $lap", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Current lap time: ${formatMs(currentLapMs)}")
            Text("Best lap time: ${formatMs(bestLapMs)}")

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = "At corner: $atCorner",
                fontSize = 32.sp // Add this line
            )
            Text(
                text = "Target corner idx: ${targetCornerIdx + 1}",
                fontSize = 32.sp // Add this line
            )
            Text(
                text = "Dist to target (m): ${
                    if (distToTargetCornerM.isNaN()) "—" else String.format("%.1f", distToTargetCornerM)
                }",
                fontSize = 32.sp // Add this line
            )

/*
                Text("In S/F zone: $insideSF")
                Text("Dist to S/F (m): $distStr")
                Text("GPS: $latStr, $lonStr")
                Text("GPS ticks: $gpsTicks")
                Text("Dist to S/F center (m): $distStr")
                Text("Dist to S/F edge (m): $edgeStr")


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
    */
        }
    }
}

private fun formatMs(ms: Long?): String =
    if (ms == null) "—" else {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1_000
        val hundredths = (ms % 1_000) / 10
        String.format("%d:%02d.%02d", minutes, seconds, hundredths)
    }

private fun checkIfAtCornerLua(
    track: Track,
    lat: Double,
    lon: Double,
    atCorner: Boolean,
    targetCornerIdx: Int // 0-based
): Triple<Boolean, Int, Double /*distToTarget*/> {

    val tol = track.cornerToleranceM
    val corners = track.corners
    if (corners.isEmpty()) return Triple(false, 0, Double.NaN)

    var newAtCorner = atCorner
    var newTarget = targetCornerIdx
    var distToTarget = Double.NaN

    // for i = 1..num_corners
    corners.forEachIndexed { i, c ->
        val delta = haversineMeters(c.lat, c.lon, lat, lon)

        // if i == target_corner then distance_to_target_corner = delta
        if (i == newTarget) {
            distToTarget = delta
        }

        // if delta < corner_tolerance and not at_corner then
        if (delta < tol && !newAtCorner) {
            newTarget = (i + 1) % corners.size   // target_corner = i + 1 (wrap)
            newAtCorner = true                   // at_corner = true
            return@forEachIndexed                // break
        }

        // if delta >= corner_tolerance then at_corner = false
        if (delta >= tol) {
            newAtCorner = false
        }
    }

    // wrap already handled by modulo above
    return Triple(newAtCorner, newTarget, distToTarget)
}
