package com.example.hotlapmobile.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
/* STEP: Import track config + distance helper */
import com.example.hotlapmobile.config.Track
import com.example.hotlapmobile.config.Tracks
import com.example.hotlapmobile.util.haversineMeters
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.data.TrackRepo
import androidx.compose.ui.platform.LocalContext

//imports for the hardware accelerometers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.example.hotlapmobile.data.PrefsRepo
import kotlin.math.sqrt
import kotlin.math.exp  // for time-aware EMA alpha
import kotlin.math.min


import com.example.hotlapmobile.data.CalibRepo
import com.example.hotlapmobile.data.CalibState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

import androidx.compose.foundation.Canvas

import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.toSize

import com.example.hotlapmobile.config.LatLon

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding




enum class DrivePhase { BRAKING, COASTING, ACCELERATING, UNKNOWN }


//stream the accelerometer sensor and fill longG based on +X axis
@Composable
private fun AccelProducer(latest: MutableState<LatestInputs>) {
    val ctx = LocalContext.current

// --- EMA smoothing state ---
// Remember previous smoothed value (g) so we can apply low-pass filtering
    var emaG by remember { mutableStateOf<Float?>(null) }

// Remember previous sensor timestamp (ns) to make the smoothing time-aware
    var lastTsNs by remember { mutableStateOf<Long?>(null) }


    // read the calibrated forward unit vector (falls back to +X if not set)
    val calibRepo = remember(ctx) { CalibRepo(ctx) }
    val calibState = calibRepo.state
        .collectAsStateWithLifecycle(initialValue = CalibState(vec = null, savedAtEpochMs = null))
        .value

// Build a normalized forward vector from saved state; default to +X if missing/zero
    val (fx, fy, fz) = run {
        val v = calibState.vec ?: floatArrayOf(1f, 0f, 0f)
        val n = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).let { if (it == 0f) 1f else it }
        Triple(v[0]/n, v[1]/n, v[2]/n)
    }


    DisposableEffect(fx, fy, fz) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (lin == null) {
            latest.value = latest.value.copy(longG = null)
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

                // 1) Read device linear acceleration in m/s²
                val ax = e.values[0]
                val ay = e.values[1]
                val az = e.values[2]

                // 2) Project onto calibrated forward axis
                val projMs2 = fx * ax + fy * ay + fz * az
                val gProj = projMs2 / ONE_G

                // 3) Time-aware EMA smoothing + clamp + deadband
                val nowNs = e.timestamp                        // sensor monotonic ns
                val last = lastTsNs                            // previous ns
                val gRaw = gProj.coerceIn(-G_CLAMP, G_CLAMP)   // guard against spikes
                val dtMs = if (last != null) (nowNs - last) / 1_000_000f else 0f
                lastTsNs = nowNs

                // alpha from time constant TAU_MS
                val alpha = if (dtMs > 0f) (1f - exp(-dtMs / TAU_MS)) else 1f

                // first sample uses raw; thereafter blend
                val emaPrev = emaG ?: gRaw
                val emaNow = emaPrev + alpha * (gRaw - emaPrev)
                emaG = emaNow

                // deadband around zero for coast stability
                val gSmooth = if (kotlin.math.abs(emaNow) < DEAD_BAND_G) 0f else emaNow

                // 4) Publish smoothed g
                latest.value = latest.value.copy(longG = gSmooth)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, lin, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
}

// Hold one GPS reading (latitude, longitude) with a monotonic time tag (tMillis).
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val tMillis: Long,
    val speedMps: Float = 0f // from android.os.SystemClock.elapsedRealtime()
)

 //LatestInputs holds the most recent sensor readings we care about.
data class LatestInputs(
    val gps: GpsFix? = null,
    val prevGps: GpsFix? = null,
    val longG: Float? = null      // longitudinal accel in g, projected on calibrated forward
)

//Prepare fields we'll need for S/F detection and lap counting.
data class WorldState(
    val lapCount: Int = 0,
    val distToStartM: Double? = null,
    val inStartZone: Boolean = false,
    val wasInStartZone: Boolean = false,

    // --- Lap timing ---
    val currentLapStartMs: Long? = null,
    val currentLapElapsedMs: Long? = null,
    val bestLapMs: Long? = null,
    val lastSfEnterMs: Long? = null,

    // --- Corner detection (Lua parity) ---
    val atCorner: Boolean = false,
    val targetCornerIdx: Int = 0,          // 0-based
    val distToTargetCornerM: Double? = null,

    // --- Brake-point scaffolding ---
    val candidateBrakePts: Map<Int, LatLon> = emptyMap(),
    val fastestBrakePts: Map<Int, LatLon> = emptyMap(),
    val lastBrakeCaptureNote: String? = null,


    // current GPS fix (nullable until your pipeline sets it each tick)
    val currentLatLon: LatLon? = null,

// braking phase flag for this tick (set by your detector elsewhere)
    val isBrakingNow: Boolean = false,

    val targetBrakePoint: LatLon? = null, // NEW: recorded brake point of the current target corner

    // NEW: snapshot captured at the most recent valid GPS fix
    val lastFixTimeMs: Long? = null,
    val lastFixDistToBP_M: Double? = null,
    val lastFixSpeedMps: Double? = null,

    val countdownShow: Boolean = false,
    val countdownSeconds: Int? = null,  // 0..N
    val countdownRingFrac: Float? = null  // 0f..1f
)


//helpers to convert sensor readings to g's and project acceleration onto calibrated forward axis.

private const val ONE_G = 9.80665f   // conversion from m/s² → g
// --- Longitudinal g smoothing (EMA) ---
// Max believable g before smoothing (guards against IMU spikes)
private const val G_CLAMP = 1.8f
// Low-pass time constant in milliseconds (lower = snappier, higher = smoother)
private const val TAU_MS = 300f
// Deadband around zero after smoothing to keep “coast” steady
private const val DEAD_BAND_G = 0.02f


private fun dot3(a: FloatArray, x: Float, y: Float, z: Float): Float {
    return a[0] * x + a[1] * y + a[2] * z
}

@Composable
fun rememberWorldState(): MutableState<WorldState> =
    remember { mutableStateOf(WorldState()) }

/* GPS Producer - publish latest GPS fix into the mailbox.
 *
 * - Subscribe to fused location updates.
 * - On each new Android Location, write a GpsFix(lat, lon, tMillis) into LatestInputs.
 * - Avoid any racing logic here; this is a "dumb" data publisher.
 *
 * Notes:
 * - Requires location permission already granted (ACCESS_FINE/COARSE).
 * - Uses a relatively fast request interval (200 ms). The 10 Hz loop will decide what to do.
 * - Uses SystemClock.elapsedRealtime() for a monotonic timestamp (safe for age calculations).
 */
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun GpsProducer(
    latest: MutableState<LatestInputs>,
    world: MutableState<WorldState>   // NEW
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(Unit) {
        val fused = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(ctx)

        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            200L // request ~5 Hz; Android will coalesce as needed
        ).setMinUpdateIntervalMillis(200L).build()




        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(res: com.google.android.gms.location.LocationResult) {
                val loc = res.lastLocation ?: return

                val nowMs = android.os.SystemClock.elapsedRealtime()



                val tbp = world.value.targetBrakePoint
                if (tbp == null) android.util.Log.d("GPS", "dist→BrakePoint = (none)")

                tbp?.let { bp ->
                    val dToBP = haversineMeters(loc.latitude, loc.longitude, bp.lat, bp.lon)
                    android.util.Log.d("GPS", "dist→BrakePoint = ${"%.1f".format(dToBP)} m")

                    world.value = world.value.copy(
                        lastFixTimeMs = nowMs,
                        lastFixDistToBP_M = dToBP,
                        lastFixSpeedMps = loc.speed.toDouble()
                    )

                    android.util.Log.d(
                        "GPS",
                        "Snap(last): t=${nowMs}, dBP=${"%.1f".format(dToBP)} m, v=${"%.2f".format(loc.speed)} m/s"
                    )
                }

                android.util.Log.d(
                    "GPS",
                    "Fix: lat=${loc.latitude}, lon=${loc.longitude}, speed=${"%.2f".format(loc.speed)}, time=$nowMs"
                )
                val prev = latest.value.gps   // grab current (will become previous)
                // ---------------------------

                // If we have a previous fix, compute between-fix distance & segment speed
                prev?.let { p ->
                    val dMeters = haversineMeters(p.lat, p.lon, loc.latitude, loc.longitude)
                    val dtSec = (nowMs - p.tMillis) / 1000.0
                    if (dtSec > 0.0) {
                        val segSpeedMps = dMeters / dtSec
                        android.util.Log.d(
                            "GPS",
                            "segΔ: d=${"%.1f".format(dMeters)} m, dt=${"%.2f".format(dtSec)} s, v=${"%.2f".format(segSpeedMps)} m/s"
                        )
                    }
                    //calculate the bearing between the previous and current GPS fix
                    val segBearing = bearingDeg(p.lat, p.lon, loc.latitude, loc.longitude)
                    android.util.Log.d(
                        "GPS",
                        "segBearing=${"%.1f".format(segBearing)}°"
                    )

                }


//capture and save teh previous value.
                latest.value = latest.value.copy(
                    prevGps = prev,
                    gps = GpsFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        tMillis = nowMs,
                        speedMps = loc.speed
                    )
                )



                android.util.Log.d(
                    "GPS",
                    "prevGps is ${if (latest.value.prevGps == null) "null" else "set"}"
                )



            }

        }



        fused.requestLocationUpdates(req, cb, ctx.mainLooper)
        onDispose { fused.removeLocationUpdates(cb) }
    }
}



@Composable
fun rememberLatestInputsMailbox(): MutableState<LatestInputs> {
    return remember { mutableStateOf(LatestInputs()) }
}


// allows swipe between a "Racing UI — coming soon" page and the live Debug page.

@Composable
fun RacingScreen() {
    // 10 Hz heartbeat counter
    var ticks by remember { mutableStateOf(0L) }

    // Mailbox for latest sensor inputs
    val latest = rememberLatestInputsMailbox()
    val world = rememberWorldState()
    // --- Track selection from DataStore ---
    val context = LocalContext.current
    val trackRepo = remember(context) { TrackRepo(context) }
    val selectedTrack = trackRepo.current.collectAsStateWithLifecycle(initialValue = null).value
    val track = selectedTrack ?: Tracks.DcfNeighborhood   // fallback if none chosen




    val brakeWarnTimeS = track.brakeWarnTimeS


    //context for braking and accelerating
    val prefsRepo = remember(context) { PrefsRepo(context) }
    val brakeThreshG = prefsRepo.brakeThreshG
        .collectAsStateWithLifecycle(initialValue = 0.20f) // default if unset
        .value
    var phase by remember { mutableStateOf(DrivePhase.UNKNOWN) }


    // start streaming linear acceleration → latest.value.longG
    AccelProducer(latest)

    // Start GPS producer
    GpsProducer(latest, world)

    LaunchedEffect(Unit) {
        while (true) {
            // 1) Start/Finish + corner
            world.value = checkIfAtStartFinish(latest.value, world.value, track)
            world.value = updateCornerState(latest.value, world.value, track)

            // 2) Detect phase for this tick
            val phaseNow = detectDrivePhase(latest.value.longG, brakeThreshG)

            // 3) Update world with current GPS fix + braking flag
            val fix = latest.value.gps
            world.value = world.value.copy(
                currentLatLon = fix?.let { LatLon(it.lat, it.lon) },
                isBrakingNow = (phaseNow == DrivePhase.BRAKING)
            )

            // 4) Try capturing a candidate brake point (tidy one-liner)
            world.value = updateBrakePointState(world.value, track)

            // 5) Any other per-tick bookkeeping you keep (optional)
            // phase = phaseNow   // if you show it elsewhere

            // Use the recorded fastest brake point for the current target corner (if any)
            val tbp = world.value.fastestBrakePts[world.value.targetCornerIdx]
            if (tbp != world.value.targetBrakePoint) {
                world.value = world.value.copy(targetBrakePoint = tbp)
            }

            //extrapolating from last GPS fix to time to brake
            val tToBrake = extrapolatedTimeToBrake(world.value)
            if (tToBrake != null) {
                val out = countdownFrom(tToBrake, track.brakeWarnTimeS)
                world.value = world.value.copy(
                    countdownShow = out.show,
                    countdownSeconds = if (out.show) out.secondsInt else null,
                    countdownRingFrac = if (out.show) out.ringFrac.coerceIn(0f, 1f) else null
                )
            } else {
                // hide when we don’t have a valid estimate
                world.value = world.value.copy(
                    countdownShow = false,
                    countdownSeconds = null,
                    countdownRingFrac = null
                )
            }








            ticks++

            delay(100L)
        }
    }

    // ---- UI: two pages
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    Column(modifier = Modifier.fillMaxSize()) {
        // 👇 Dev buttons row at the top
        DevPanel(world = world, latest = latest)

        // 👇 Pager takes the rest of the space
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> RacingUi(world = world.value, track = track, g = latest.value.longG ?: 0f)
                1 -> DebugUi(
                    ticks = ticks,
                    latest = latest.value,
                    world = world.value,
                    track = track,
                    phase = phase,
                    brakeThreshG = brakeThreshG
                )
            }
        }
    }


}


/*
 *
 * Purpose:
 * - Placeholder only; we'll build the countdown UI later.
 */
@Composable
private fun RacingUi(world: WorldState, track: Track, g: Float) {
    Box(modifier = Modifier.fillMaxSize()) {

        // TOP: Track name
        Text(
            text = track.name,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )

        // NEW: corner dots near the top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        ) {
            BrakePointDots(track = track, world = world)
        }


        if (world.countdownShow && world.countdownSeconds != null) {
            Text(
                text = "${world.countdownSeconds}",
                fontSize = 80.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }



// RIGHT: one combined indicator (bar behind, gray box on top)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            RightGIndicator(g = g, maxAbs = 0.5f) // tighter range so you can see movement easily
        }


        // BOTTOM: Current / Best / Lap counter
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Target Corner: ${world.targetCornerIdx+1}", fontSize = 32.sp)
            Text(text = "Current Lap: ${formatMs(world.currentLapElapsedMs)}", fontSize = 32.sp)
            Text(text = "Best: ${formatMs(world.bestLapMs)}", fontSize = 28.sp)
            Text(text = "Lap: ${world.lapCount}", fontSize = 22.sp)
        }
    }
}



/*
 * DebugUi: live debug panel showing internal values.
 *
 * Purpose:
 * - Display internal variables, state flags, and sensor snapshots.
 * - Right now: shows tick count, GPS lat/lon, and GPS sample age.
 */
@Composable
private fun DebugUi(
    ticks: Long,
    latest: LatestInputs,
    world: WorldState,
    track: Track,
    phase: DrivePhase,
    brakeThreshG: Float
) {
    val gps = latest.gps
    val ageMs = gps?.let { android.os.SystemClock.elapsedRealtime() - it.tMillis }
    val brakeWarnTimeS = track.brakeWarnTimeS


    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Text("DEBUG V1.1", fontSize = 22.sp)
        androidx.compose.material3.Text("Tick: $ticks")
        androidx.compose.material3.Text("GPS lat: ${gps?.lat}")
        androidx.compose.material3.Text("GPS lon: ${gps?.lon}")
        androidx.compose.material3.Text("GPS age ms: ${ageMs ?: "n/a"}")





        // start/finish info
        val dStr = world.distToStartM?.let { String.format("%.1f m", it) } ?: "n/a"
        androidx.compose.material3.Text("Start/Finish dist: $dStr")
        androidx.compose.material3.Text("In S/F zone: ${world.inStartZone}")

        // lap counter
        Text("Lap: ${world.lapCount}")

        // --- NEW: Lap timing ---
        Text("Current lap: ${formatMs(world.currentLapElapsedMs)}")
        Text("Best lap:    ${formatMs(world.bestLapMs)}")

        Text(
            text = buildString {
                append("Phase: ${phase.name}\n")
                append("Longitudinal accel: ${"%.2f".format(latest.longG ?: 0f)} g\n")
                append("Threshold: \u00B1${"%.2f".format(brakeThreshG)} g")
            },
            color = Color.Gray,
            fontSize = 16.sp
        )

        Text("Target corner: ${world.targetCornerIdx+1}")
        Text("Dist to target: ${world.distToTargetCornerM?.let { "%.1f m".format(it) } ?: "--"}")
        Text("At corner: ${world.atCorner}")

        Text("Fastest brake pt for target? " +
                if (world.fastestBrakePts.containsKey(world.targetCornerIdx)) "yes" else "no")
        Text("Last capture: ${world.lastBrakeCaptureNote ?: "--"}")

        Text("Target corner: ${world.targetCornerIdx+1}")
        Text("Dist to target: ${world.distToTargetCornerM?.let { "%.1f m".format(it) } ?: "--"}")
        Text("At corner: ${world.atCorner}")

// --- New brake-point debug ---
        val dFast = distToTargetFastestBrakePoint(world)?.let { "%.1f m".format(it) } ?: "--"
        Text("Dist to target brake pt: $dFast")
        Text("Brake warn distance: ${"%.0f m".format(track.brakeWarnDistanceM)}")
        Text("Last capture: ${world.lastBrakeCaptureNote ?: "--"}")

        Text(
            text = "Brake Warn Time: ${"%.1f".format(brakeWarnTimeS)} s",
            color = Color.Gray,
            fontSize = 14.sp
        )

        Text("TBP: ${if (world.targetBrakePoint == null) "none" else "set"}")



    }
}

@Composable
private fun DevPanel(world: MutableState<WorldState>, latest: MutableState<LatestInputs>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .statusBarsPadding()
            .padding(12.dp)
    ) {
        Button(onClick = {
            latest.value.gps?.let { g ->
                world.value = world.value.copy(targetBrakePoint = LatLon(g.lat, g.lon))
            }
        }) { Text("Dev: Set TBP = Here") }

        Button(onClick = {
            world.value = world.value.copy(targetBrakePoint = null)
        }) { Text("Dev: Clear TBP") }
    }
}



@Composable
private fun GReadoutBox(g: Float, modifier: Modifier = Modifier) {
    val label = "${"%.1f".format(g)} g"

    Box(
        modifier = modifier
            .width(90.dp)            // wider
            .height(40.dp)          // taller gray band
            .background(Color(0xFFD9D9D9), RoundedCornerShape(10.dp))
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 28.sp,
            color = Color.Black
        )
    }
}




//function that determines the distance from start/finish and whether we are in the start finish zone
private fun updateStartZone(
    inputs: LatestInputs,
    world: WorldState,
    track: Track
): WorldState {
    val fix = inputs.gps ?: return world.copy(distToStartM = null, inStartZone = false)

    val d = haversineMeters(
        fix.lat, fix.lon,
        track.startFinish.lat, track.startFinish.lon
    )
    val inZone = d <= track.startFinishRadiusM



    return world.copy(
        distToStartM = d,
        inStartZone = inZone
    )
}

// Rising-edge latch only: just carry wasInStartZone forward.
// Do NOT touch lapCount or lastSfEnterMs here.
private fun updateLapOnStartZone(world: WorldState): WorldState {
    return world.copy(wasInStartZone = world.inStartZone)
}


//LAP TIMER - Update current lap elapsed time each 10 Hz tick.
private fun tickUpdateLapElapsed(world: WorldState): WorldState {
    val start = world.currentLapStartMs ?: return world
    val now = android.os.SystemClock.elapsedRealtime()
    val elapsed = now - start
    return world.copy(currentLapElapsedMs = elapsed)
}

//Starting the lap timer on the first start/finish crossing
private fun startLapOnFirstCrossing(world: WorldState): WorldState {
    if (world.currentLapStartMs != null) return world
    val now = android.os.SystemClock.elapsedRealtime()
    if (!isEnteringAllowed(world, now)) {
        // even if not allowed, carry the latch forward:
        return world
    }
    return world.copy(
        currentLapStartMs = now,
        currentLapElapsedMs = 0L,
        lastSfEnterMs = now
    )
}


private fun finishLapOnCrossing(world: WorldState): WorldState {
    val start = world.currentLapStartMs ?: return world
    val now = android.os.SystemClock.elapsedRealtime()
    if (!isEnteringAllowed(world, now)) return world

    val lapMs = now - start
    val wasBest = (world.bestLapMs == null) || (lapMs < world.bestLapMs!!)
    val newBest = world.bestLapMs?.let { kotlin.math.min(it, lapMs) } ?: lapMs

    val rolled = world.copy(
        lapCount = world.lapCount + 1,     // 🔹 move lap increment here
        bestLapMs = newBest,
        currentLapStartMs = now,           // immediately start next lap
        currentLapElapsedMs = 0L,
        lastSfEnterMs = now
    )

    return promoteBrakeCandidatesIfPB(rolled, wasBest)
}




//(Lap Timer UI): Add a millisecond→text formatter.
private fun formatMs(ms: Long?): String {
    if (ms == null) return "--:--.---"
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    val millis  = ms % 1_000
    return String.format("%d:%02d.%03d", minutes, seconds, millis)
}

//function to check if we're at the start finish and update the world state accordingly
private fun checkIfAtStartFinish(
    inputs: LatestInputs,
    world: WorldState,
    track: Track
): WorldState {
    var w = world
    w = updateStartZone(inputs, w, track)   // compute inStartZone
    w = startLapOnFirstCrossing(w)          // start timing on the very first crossing
    w = finishLapOnCrossing(w)              // close subsequent laps
    w = updateLapOnStartZone(w)             // carry latch forward
    w = tickUpdateLapElapsed(w)             // update running time
    return w
}


//debounce cooldowwn timer for start/finish cross.

private const val COOLDOWN_MS = 2000L  // 2s is conservative; tune after testing

/*
 * True iff this tick is a rising edge AND the cooldown has elapsed.
 */
private fun isEnteringAllowed(world: WorldState, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
    val entering = world.inStartZone && !world.wasInStartZone
    if (!entering) return false
    val last = world.lastSfEnterMs ?: return true
    return (now - last) >= COOLDOWN_MS
}

//update the corner
private fun updateCornerState(
    latest: LatestInputs,
    world: WorldState,
    track: Track
): WorldState {
    val fix = latest.gps ?: return world  // no GPS yet

    val (atC, nextIdx, distM) = checkIfAtCornerLua(
        track = track,
        lat = fix.lat,
        lon = fix.lon,
        atCorner = world.atCorner,
        targetCornerIdx = world.targetCornerIdx
    )

    return world.copy(
        atCorner = atC,
        targetCornerIdx = nextIdx,
        distToTargetCornerM = distM
    )
}




//Lua-parity: check_if_at_corner()



private fun checkIfAtCornerLua(
    track: Track,
    lat: Double,
    lon: Double,
    atCorner: Boolean,
    targetCornerIdx: Int // 0-based
): Triple<Boolean, Int, Double> {

    val tol = track.cornerToleranceM
    val corners = track.corners
    if (corners.isEmpty()) return Triple(false, 0, Double.NaN)

    var newAtCorner = atCorner
    var newTarget = targetCornerIdx
    var distToTarget = Double.NaN

    corners.forEachIndexed { i, c ->
        val delta = haversineMeters(c.lat, c.lon, lat, lon)

        // if i == target_corner then set distance to target corner
        if (i == newTarget) {
            distToTarget = delta
        }

        // if delta < tol and not at_corner then we "hit" this corner -> advance target
        if (delta < tol && !newAtCorner) {
            newTarget = (i + 1) % corners.size
            newAtCorner = true
            return@forEachIndexed // break
        }

        // if delta >= tol then we are not at a corner (clears the latch once we leave)
        if (delta >= tol) {
            newAtCorner = false
        }
    }

    return Triple(newAtCorner, newTarget, distToTarget)
}



private fun detectDrivePhase(longG: Float?, threshold: Float): DrivePhase {
    val g = longG ?: return DrivePhase.UNKNOWN
    return when {
        g <= -threshold -> DrivePhase.BRAKING
        g >=  threshold -> DrivePhase.ACCELERATING
        else -> DrivePhase.COASTING
    }
}

@Composable
private fun GArrowBar(
    g: Float,
    maxAbs: Float = 1.5f,
    gain: Float = 2.0f,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(90.dp)
            .height(220.dp)
    ) {
        val w = size.width
        val h = size.height
        val half = h / 2f

        val deadband = 0.02f
        val clamped = g.coerceIn(-maxAbs, maxAbs)
        val frac = min((kotlin.math.abs(clamped) / maxAbs) * gain, 1f)

        // color by direction
        val barColor = when {
            clamped > deadband  -> Color(0xFF16A34A) // green
            clamped < -deadband -> Color(0xFFDC2626) // red
            else                -> Color(0xFF9CA3AF) // gray (coast)
        }

        // ensure a small visible bar whenever outside deadband
        val minLenPx = if (abs(clamped) > deadband) 6.dp.toPx() else 0f
        val len = maxOf(half * frac, minLenPx)

        val radius = 4.dp.toPx()

        if (clamped >= 0f) {
            // UP from center
            drawRoundRect(
                color = barColor,
                topLeft = Offset(0f, half - len),
                size = Size(w, len),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )
        } else {
            // DOWN from center
            drawRoundRect(
                color = barColor,
                topLeft = Offset(0f, half),
                size = Size(w, len),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )
        }
    }
}

@Composable
private fun RightGIndicator(g: Float, maxAbs: Float = 1.5f) {
    Box(
        modifier = Modifier
            .width(100.dp)
            .height(220.dp)
    ) {
        // BACK: vertical bar
        GArrowBar(
            g = g,
            maxAbs = maxAbs,
            modifier = Modifier
                .align(Alignment.CenterEnd) // right edge
        )
        // FRONT: gray box centered (covers middle so bar grows above/below it)
        GReadoutBox(
            g = g,
            modifier = Modifier
                .align(Alignment.CenterEnd)
        )
    }
}


// Lua-parity helper: decide if we should capture a brake point *now* for the target corner.
// Pure function: no side effects.
private fun shouldCaptureBrakeForCorner(
    phaseIsBraking: Boolean,          // from your drive-phase detector
    atCorner: Boolean,                // world.atCorner (within tight corner tolerance)
    distToTargetCornerM: Double?,     // world.distToTargetCornerM
    brakeZoneDistanceM: Double,       // track.brakeZoneDistanceM (Lua: brake_zone_distance)
    alreadyHasCandidate: Boolean      // world.candidateBrakePts.containsKey(targetCornerIdx)
): Boolean {
    if (!phaseIsBraking) return false
    if (alreadyHasCandidate) return false

    val d = distToTargetCornerM ?: return false
    if (d.isNaN() || d.isInfinite()) return false

    // Lua: capture when IN brake zone + braking; Kotlin: require we're not *at* the corner yet.
    // This prevents grabs inside the corner tolerance while preserving the Lua "in zone" behavior.
    return (d <= brakeZoneDistanceM) && !atCorner
}


// Result of attempting a candidate capture
private data class CandidateUpdateResult(
    val candidateBrakePts: Map<Int, LatLon>,
    val lastBrakeCaptureNote: String?
)

// Store a first-time candidate brake point for the *current* target corner.
// Pure: returns updated map + optional debug note. No WorldState dependency.
private fun updateCandidateBrakePoint(
    targetCornerIdx: Int,
    atCorner: Boolean,
    distToTargetCornerM: Double?,
    phaseIsBraking: Boolean,
    brakeZoneDistanceM: Double,
    candidateBrakePts: Map<Int, LatLon>,
    current: LatLon
): CandidateUpdateResult {
    // Validate corner index
    if (targetCornerIdx < 0) return CandidateUpdateResult(candidateBrakePts, null)

    // Decide whether to capture (Lua-parity logic)
    val alreadyHasCandidate = candidateBrakePts.containsKey(targetCornerIdx)
    val capture = shouldCaptureBrakeForCorner(
        phaseIsBraking = phaseIsBraking,
        atCorner = atCorner,
        distToTargetCornerM = distToTargetCornerM,
        brakeZoneDistanceM = brakeZoneDistanceM,
        alreadyHasCandidate = alreadyHasCandidate
    )
    if (!capture) return CandidateUpdateResult(candidateBrakePts, null)

    // Record first capture for this corner this lap
    val updated = candidateBrakePts.toMutableMap().apply { put(targetCornerIdx, current) }

    fun fmt(v: Double) = "%.6f".format(v)
    val note = "Captured C$targetCornerIdx at ${fmt(current.lat)},${fmt(current.lon)}"

    return CandidateUpdateResult(updated, note)
}

// Wrapper to keep the 10 Hz loop tidy.
// Decides whether to capture a brake point and returns the updated WorldState.
private fun updateBrakePointState(
    world: WorldState,
    track: Track
): WorldState {
    val fix = world.currentLatLon ?: return world

    val res = updateCandidateBrakePoint(
        targetCornerIdx      = world.targetCornerIdx,
        atCorner             = world.atCorner,
        distToTargetCornerM  = world.distToTargetCornerM,
        phaseIsBraking       = world.isBrakingNow,
        brakeZoneDistanceM   = track.brakeZoneDistanceM,
        candidateBrakePts    = world.candidateBrakePts,
        current              = fix
    )

    return if (res.lastBrakeCaptureNote == null &&
        res.candidateBrakePts === world.candidateBrakePts) {
        world
    } else {
        world.copy(
            candidateBrakePts = res.candidateBrakePts,
            lastBrakeCaptureNote = res.lastBrakeCaptureNote ?: world.lastBrakeCaptureNote
        )
    }
}

private fun distToTargetFastestBrakePoint(world: WorldState): Double? {
    val fix = world.currentLatLon ?: return null
    val bp  = world.fastestBrakePts[world.targetCornerIdx] ?: return null
    return haversineMeters(fix.lat, fix.lon, bp.lat, bp.lon)
}

// If `isPB` is true, promote all candidates → fastest; always clear candidates afterward.
private fun promoteBrakeCandidatesIfPB(world: WorldState, isPB: Boolean): WorldState {
    val promotedCount = if (isPB) world.candidateBrakePts.size else 0
    val newFastest = if (isPB) {
        world.fastestBrakePts.toMutableMap().apply { putAll(world.candidateBrakePts) }
    } else {
        world.fastestBrakePts
    }
    val note = if (isPB) "Promoted $promotedCount brake point(s)" else null
    return world.copy(
        fastestBrakePts = newFastest,
        candidateBrakePts = emptyMap(),                // start fresh for the next lap
        lastBrakeCaptureNote = note ?: world.lastBrakeCaptureNote
    )
}


@Composable
private fun BrakePointDots(track: Track, world: WorldState) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        track.corners.forEachIndexed { i, _ ->
            val recorded = world.candidateBrakePts.containsKey(i)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "C${i + 1}", fontSize = 18.sp)
                Text(
                    text = "●",
                    fontSize = 38.sp,
                    color = if (recorded) Color(0xFF22C55E) else Color.Gray
                )
            }
        }
    }
}


private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δλ = Math.toRadians(lon2 - lon1)
    val y = Math.sin(Δλ) * Math.cos(φ2)
    val x = Math.cos(φ1) * Math.cos(φ2) * Math.cos(Δλ) - Math.sin(φ1) * Math.sin(φ2)
    var θ = Math.toDegrees(Math.atan2(y, x))
    if (θ < 0) θ += 360.0
    return θ
}


private fun extrapolatedTimeToBrake(world: WorldState): Double? {
    val tFix = world.lastFixTimeMs ?: run {
        android.util.Log.d("BRAKE", "skip: no lastFixTimeMs")
        return null
    }
    val dFix = world.lastFixDistToBP_M ?: run {
        android.util.Log.d("BRAKE", "skip: no lastFixDistToBP_M")
        return null
    }
    val vFix = world.lastFixSpeedMps ?: run {
        android.util.Log.d("BRAKE", "skip: no lastFixSpeedMps")
        return null
    }
    if (vFix <= 0.01) {  // very low so indoor tests work
        android.util.Log.d("BRAKE", "skip: vFix too small ($vFix m/s)")
        return null
    }

    val nowMs = android.os.SystemClock.elapsedRealtime()
    val dt = (nowMs - tFix) / 1000.0
    val dNow = (dFix - vFix * dt).coerceAtLeast(0.0)
    val t = dNow / vFix
    android.util.Log.d("BRAKE", "ok: dFix=${"%.2f".format(dFix)} dNow=${"%.2f".format(dNow)} vFix=${"%.2f".format(vFix)} t=${"%.2f".format(t)}")
    return t
}

private data class CountdownOut(
    val show: Boolean,
    val secondsInt: Int,   // 0..N
    val ringFrac: Float    // 0.0..1.0 (0 = just ticked over to new second, 1 = almost next)
)

private fun countdownFrom(tToBrake: Double, warnTimeS: Double): CountdownOut {
    if (tToBrake > warnTimeS) return CountdownOut(false, 0, 0f)
    if (tToBrake < 0.0) return CountdownOut(true, 0, 1f) // already at/inside BP
    val secondsInt = kotlin.math.floor(tToBrake).toInt()
    val fracWithinSecond = (tToBrake - secondsInt).toFloat() // 0.00..0.99
    // Map “fraction of second remaining” → ring sweep (1.0 = full circle, 0.0 = just ticked)
    val ringFrac = 1f - fracWithinSecond
    return CountdownOut(true, secondsInt, ringFrac)
}
