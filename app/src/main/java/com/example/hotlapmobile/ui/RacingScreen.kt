package com.example.hotlapmobile.ui

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.config.Track
import com.example.hotlapmobile.data.CalibRepo
import com.example.hotlapmobile.data.CalibState
import com.example.hotlapmobile.data.TrackRepo
import com.example.hotlapmobile.util.haversineMeters
import kotlinx.coroutines.delay
import com.example.hotlapmobile.data.PrefsRepo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape





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

    var longG by remember { mutableStateOf(0f) } // placeholder; will wire sensors later
    var emaLong by remember { mutableStateOf<Float?>(null) }
    val emaAlpha = 0.20f   // tweak 0.1–0.3 to taste


    val calibRepo = remember(ctx) { CalibRepo(ctx) }
    val calibState = calibRepo.state.collectAsStateWithLifecycle(
        initialValue = CalibState(vec = null, savedAtEpochMs = null)
    ).value
    val forwardVec = calibState.vec  // FloatArray?  (null until calibrated)

    val prefsRepo = remember(ctx) { PrefsRepo(ctx) }
    val brakeThreshG = prefsRepo.brakeThreshG
        .collectAsStateWithLifecycle(initialValue = 0.2f).value
    val gDeadband = brakeThreshG


    // Per-corner brake capture state (size = number of corners)
    val cornerCount = track?.corners?.size ?: 0
    var brakeRecorded by remember(track?.name) { mutableStateOf(MutableList(cornerCount) { false }) }
    var brakeLat by remember(track?.name) { mutableStateOf(MutableList<Double?>(cornerCount) { null }) }
    var brakeLon by remember(track?.name) { mutableStateOf(MutableList<Double?>(cornerCount) { null }) }
    var captureMsg by remember { mutableStateOf<String?>(null) }

    // Per-corner brake points for the *fastest lap*
    var bestBrakeLat by remember(track?.name) { mutableStateOf(MutableList<Double?>(cornerCount) { null }) }
    var bestBrakeLon by remember(track?.name) { mutableStateOf(MutableList<Double?>(cornerCount) { null }) }



// Brake detected when longitudinal g is more negative than the user-set threshold
    val brakeDetected = longG <= -brakeThreshG

// In-brake-zone when distance to the *target* corner is within track.brakeZoneDistanceM
    val inBrakeZone = track != null &&
            !distToTargetCornerM.isNaN() &&
            distToTargetCornerM <= track.brakeZoneDistanceM

    var distToBestBrakeM by remember { mutableStateOf(Double.NaN) }
    var prevDistToBestBrakeM by remember { mutableStateOf<Double?>(null) }
    var approachingBrake by remember { mutableStateOf(false) }


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
                        if (bestLapMs == null || lapTime < bestLapMs!!) {
                            bestLapMs = lapTime

                            // Copy current-lap brake points into best-lap arrays where present
                            val newBestLat = bestBrakeLat.toMutableList()
                            val newBestLon = bestBrakeLon.toMutableList()
                            for (i in 0 until cornerCount) {
                                val latVal = brakeLat.getOrElse(i) { null }
                                val lonVal = brakeLon.getOrElse(i) { null }
                                if (latVal != null && lonVal != null) {
                                    newBestLat[i] = latVal
                                    newBestLon[i] = lonVal
                                }
                            }
                            bestBrakeLat = newBestLat
                            bestBrakeLon = newBestLon
                        }
                    }



                    // start timing the new lap from this crossing
                    lapStartAtMs = nowMs
                    currentLapMs = 0L

                    // Reset current-lap brake capture for the new lap
                    track?.let { t ->
                        val n = t.corners.size
                        brakeRecorded = MutableList(n) { false }
                        brakeLat = MutableList<Double?>(n) { null }
                        brakeLon = MutableList<Double?>(n) { null }
                    }


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

                // --- Distance to fastest-lap brake point for current target corner ---
                track?.let { t ->
                    val idx = targetCornerIdx.coerceIn(0, (t.corners.size - 1).coerceAtLeast(0))
                    val latBP = bestBrakeLat.getOrElse(idx) { null }
                    val lonBP = bestBrakeLon.getOrElse(idx) { null }

                    if (latBP != null && lonBP != null) {
                        val dNow = haversineMeters(latBP, lonBP, loc.latitude, loc.longitude)
                        // update approaching/prev
                        prevDistToBestBrakeM?.let { prev -> approachingBrake = dNow < prev }
                        distToBestBrakeM = dNow
                        prevDistToBestBrakeM = dNow
                    } else {
                        // no brake point saved for this corner yet
                        distToBestBrakeM = Double.NaN
                        prevDistToBestBrakeM = null
                        approachingBrake = false
                    }
                }


                // --- Brake point capture (first time per target corner) ---
                track?.let { t ->
                    val idx = targetCornerIdx.coerceIn(0, (t.corners.size - 1).coerceAtLeast(0))
                    val inZoneNow = !distToTargetCornerM.isNaN() && distToTargetCornerM <= t.brakeZoneDistanceM


                    if (
                        inZoneNow &&
                        longG <= -brakeThreshG &&                 // re-check with the live g here
                        !brakeRecorded.getOrElse(idx) { false }
                    ) {
                        val rec = brakeRecorded.toMutableList().also { it[idx] = true }
                        val blats = brakeLat.toMutableList().also { it[idx] = loc.latitude }
                        val blons = brakeLon.toMutableList().also { it[idx] = loc.longitude }
                        brakeRecorded = rec
                        brakeLat = blats
                        brakeLon = blons
                    }
                }



            }
        }

        fused.requestLocationUpdates(req, callback, ctx.mainLooper)
        onDispose { fused.removeLocationUpdates(callback) }
    }

    DisposableEffect(forwardVec) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                val ax = e.values[0]
                val ay = e.values[1]
                val az = e.values[2]

                // Project onto calibrated forward vector; fallback to device X if not calibrated
                val f = forwardVec
                val alongMs2 = if (f != null) (ax * f[0] + ay * f[1] + az * f[2]) else ax

                // Convert to g's
                val gNow = (alongMs2 / 9.80665f)
                emaLong = if (emaLong == null) gNow else (emaAlpha * gNow + (1f - emaAlpha) * emaLong!!)
                longG = emaLong!!
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }



    LaunchedEffect(lapStartAtMs) {
                while (lapStartAtMs != null) {
                    currentLapMs = android.os.SystemClock.elapsedRealtime() - lapStartAtMs!!
                    delay(200L)
                }
            }

    LaunchedEffect(captureMsg) {
        if (captureMsg != null) {
            delay(1000)
            captureMsg = null
        }
    }

    LaunchedEffect(targetCornerIdx) {
        // New target corner: clear approach history so countdown logic starts clean
        prevDistToBestBrakeM = null
        distToBestBrakeM = Double.NaN
        approachingBrake = false
    }


    // UI
    val distStr = lastDist?.let { String.format("%.1f", it) } ?: "—"
    val latStr = lastLat?.let { String.format("%.6f", it) } ?: "—"
    val lonStr = lastLon?.let { String.format("%.6f", it) } ?: "—"
    val edgeM  = lastDist?.let { it - (track?.startFinishRadiusM ?: 0.0) }
    val edgeStr = edgeM?.let { String.format("%.1f", it) } ?: "—"
    val showDebug = false

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {





        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))

            val mag = kotlin.math.abs(longG)
            val accelLabel = when {
                mag < gDeadband -> "Coasting"
                longG >= 0f -> "Accelerating"
                else -> "Braking"
            }
            Text(
                text = "$accelLabel: ${String.format("%.1f g", mag)}",
                fontSize = 48.sp,
                color = when (accelLabel) {
                    "Accelerating" -> Color.Green
                    "Braking" -> Color.Red
                    else -> Color.Gray // Coasting
                }
            )



            if (track != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    track.corners.forEachIndexed { i, _ ->
                        val recorded = brakeRecorded.getOrElse(i) { false }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "C${i + 1}", fontSize = 20.sp)
                            Text(
                                text = "●",
                                fontSize = 40.sp, // much larger dot
                                color = if (recorded) Color(0xFF22C55E) else Color.Gray
                            )
                        }
                    }
                }
            }

// ---- Brake countdown (6..0 with color-coded circle) ----
            if (track != null) {
                val warn = track.brakeWarnDistanceM         // e.g., 200 m
                val zeroEps = 5.0                           // show "0" within 5 m of the brake point

                val view = computeCountdownView(
                    distToBestBrakeM = distToBestBrakeM,
                    warnM = warn,
                    zeroEpsM = zeroEps,
                    approachingBrake = approachingBrake
                )

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(view.circleColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (view.visible) {
                        Text(
                            text = view.stage.toString(),  // 6..0
                            fontSize = 96.sp,
                            color = view.textColor
                        )
                    }
                }
            }




            captureMsg?.let {
                Text(
                    text = it,
                    color = Color(0xFF22C55E),
                    fontSize = 28.sp,
                    modifier = Modifier
                        .padding(top = 72.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Racing Screen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Track: ${track?.name ?: "— (select a track)"}")
            Spacer(Modifier.height(8.dp))
            Text("Lap: $lap", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Current lap time: ${formatMs(currentLapMs)}")
            Text("Best lap time: ${formatMs(bestLapMs)}")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "At corner: $atCorner",
                fontSize = 12.sp // Add this line
            )
            Text(
                text = "Target corner idx: ${targetCornerIdx + 1}",
                fontSize = 12.sp // Add this line
            )
            Text(
                text = "Dist to target (m): ${
                    if (distToTargetCornerM.isNaN()) "—" else String.format("%.1f", distToTargetCornerM)
                }",
                fontSize = 12.sp // Add this line
            )

            // Debug line (remove later if you want)
            Text("Brake? $brakeDetected   In zone? $inBrakeZone", fontSize = 18.sp)

            Text(
                text = "Target C${targetCornerIdx + 1}  dist=${if (distToTargetCornerM.isNaN()) "—" else String.format("%.1f", distToTargetCornerM)} m  zone≤${String.format("%.0f", track?.brakeZoneDistanceM ?: 0.0)}  inZone=$inBrakeZone",
                fontSize = 12.sp,
                color = if (inBrakeZone) Color(0xFF22C55E) else Color.Gray
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DBG  lap=$lap  bestLap=${formatMs(bestLapMs)}  curLap=${formatMs(currentLapMs)}",
                    fontSize = 18.sp,
                    color = Color.LightGray
                )

                Text(
                    text = "Target C${targetCornerIdx + 1} | distToTarget=${
                        if (distToTargetCornerM.isNaN()) "—" else String.format("%.1f", distToTargetCornerM)
                    } m | inZone=${track?.let { !distToTargetCornerM.isNaN() && distToTargetCornerM <= it.brakeZoneDistanceM } ?: false}",
                    fontSize = 18.sp,
                    color = Color.LightGray
                )

                Text(
                    text = "BestBrake dist=${
                        if (distToBestBrakeM.isNaN()) "—" else String.format("%.1f", distToBestBrakeM)
                    } m  haveBest=${!distToBestBrakeM.isNaN()}  approaching=$approachingBrake",
                    fontSize = 18.sp,
                    color = Color.LightGray
                )



                Spacer(Modifier.height(8.dp))

                Text(
                    text = "CD vis=${!distToBestBrakeM.isNaN() && distToBestBrakeM <= (track?.brakeWarnDistanceM ?: Double.MAX_VALUE) && (approachingBrake || (!distToBestBrakeM.isNaN() && distToBestBrakeM <= (track?.brakeZoneDistanceM ?: 0.0)))}  distBest=${if (distToBestBrakeM.isNaN()) "—" else String.format("%.1f", distToBestBrakeM)}  approaching=$approachingBrake",
                    fontSize = 18.sp,
                    color = Color.LightGray
                )



                Text(
                    text = "G=${String.format("%.2f", longG)}  brakeThresh=${String.format("%.2f", brakeThreshG)}  brakeDetected=${longG <= -brakeThreshG}",
                    fontSize = 18.sp,
                    color = Color.LightGray
                )


            }


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

private data class CountdownView(
    val visible: Boolean,
    val stage: Int,          // 6..0 when visible
    val circleColor: Color,
    val textColor: Color
)

private fun computeCountdownView(
    distToBestBrakeM: Double,
    warnM: Double,          // e.g., 200 m
    zeroEpsM: Double,       // e.g., 5 m  (only show 0 inside this)
    approachingBrake: Boolean
): CountdownView {
    val haveBest = !distToBestBrakeM.isNaN()
    val inWarn = haveBest && distToBestBrakeM <= warnM
    val atPoint = haveBest && distToBestBrakeM <= zeroEpsM

    // Show only when inside warn AND (approaching OR at/near brake point)
    val visible = inWarn && (approachingBrake || atPoint)
    if (!visible) {
        return CountdownView(
            visible = false,
            stage = -1,
            circleColor = Color(0xFF444444), // gray puck
            textColor = Color(0xFFB0B0B0)
        )
    }

    val stage = if (atPoint) 0
    else {
        // Map distance in (0..warn] to 1..6 (6 at warn, 1 near the point)
        kotlin.math.ceil((distToBestBrakeM / warnM) * 6.0)
            .toInt()
            .coerceIn(1, 6)
    }

    val circle = when (stage) {
        6, 5, 4 -> Color(0xFF22C55E)  // Green
        3, 2, 1 -> Color(0xFFFFC107)  // Yellow
        0       -> Color(0xFFEF4444)  // Red
        else    -> Color(0xFF444444)
    }

    return CountdownView(
        visible = true,
        stage = stage,
        circleColor = circle,
        textColor = Color.White
    )
}



private fun checkIfAtCornerLua(
    track: Track,
    lat: Double,
    lon: Double,
    atCorner: Boolean,
    targetCornerIdx: Int
): Triple<Boolean, Int, Double> {

    val tol = track.cornerToleranceM
    val corners = track.corners
    if (corners.isEmpty()) return Triple(false, 0, Double.NaN)

    var distToTarget = Double.NaN
    var nearIdx = -1

    corners.forEachIndexed { i, c ->
        val delta = haversineMeters(c.lat, c.lon, lat, lon)
        if (i == targetCornerIdx) distToTarget = delta
        if (delta < tol) nearIdx = i
    }

    val nowAtCorner = (nearIdx != -1)

    val nextTarget =
        if (!atCorner && nowAtCorner) (nearIdx + 1) % corners.size
        else targetCornerIdx

    return Triple(nowAtCorner, nextTarget, distToTarget)
}

