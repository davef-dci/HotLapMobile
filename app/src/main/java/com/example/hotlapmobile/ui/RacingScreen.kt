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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


import kotlin.math.floor
import kotlin.math.max

import android.os.SystemClock



enum class DrivePhase { BRAKING, COASTING, ACCELERATING, UNKNOWN }


// STEP 8: Dot product of two 3D vectors (utility for projections)
private fun dot(a: FloatArray, b: FloatArray): Float =
    a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

// STEP 8: Cross product of two 3D vectors (utility to compute lateral axis)
private fun cross(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(
        a[1]*b[2] - a[2]*b[1],
        a[2]*b[0] - a[0]*b[2],
        a[0]*b[1] - a[1]*b[0]
    )

// STEP 8: Normalize a 3D vector (utility to get unit vectors)
private fun normalize(v: FloatArray): FloatArray {
    val m = kotlin.math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()
    return if (m == 0f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(v[0]/m, v[1]/m, v[2]/m)
}

// STEP 8: Compute lateral g from linear acceleration, forward unit, and gravity
private fun computeLateralG(
    linearAcc: FloatArray,        // m/s^2 from TYPE_LINEAR_ACCELERATION (gravity removed)
    forwardUnit: FloatArray,      // unit vector pointing "forward" (from your calibration)
    gravity: FloatArray           // m/s^2 from TYPE_GRAVITY (used only to derive "up")
): Float {
    val upUnit = normalize(gravity)                         // derive "up" from gravity
    val lateral = normalize(cross(upUnit, forwardUnit))     // lateral axis = up × forward
    val proj = dot(linearAcc, lateral)                      // project accel onto lateral axis
    return proj / 9.80665f                                  // convert m/s^2 → g's
}

private const val COUNTDOWN_HOLD_MS: Long = 2000L


// STEP: Always-on circular ring composable (outline-only, fixed pixel conversion)
@Composable
private fun CountdownRing(
    size: Dp = 180.dp,
    strokeDp: Dp = 14.dp,
    color: Color = Color.Black
) {
    Canvas(Modifier.size(size)) {
        val stroke = strokeDp.toPx()
        val diameter = size.toPx()
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(
                diameter - stroke,
                diameter - stroke
            )
        )
    }
}



//stream the accelerometer sensor and fill longG based on +X axis
@Composable
private fun AccelProducer(latest: MutableState<LatestInputs>) {
    val ctx = LocalContext.current

// --- EMA smoothing state ---
// Remember previous smoothed value (g) so we can apply low-pass filtering
    var emaG by remember { mutableStateOf<Float?>(null) }
    var emaLatG by remember { mutableStateOf<Float?>(null) }

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

        // STEP 10a: Listener to keep the latest gravity vector (for lateral axis)
        val gravityVec = FloatArray(3)
        val gravityListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type != Sensor.TYPE_GRAVITY) return
                gravityVec[0] = e.values[0]
                gravityVec[1] = e.values[1]
                gravityVec[2] = e.values[2]
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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



                // STEP 10d: Compute lateral g from current vectors (not publishing yet)
// STEP 14b: compute + EMA smooth + deadband for lateral g
                val lat = computeLateralG(
                    linearAcc   = floatArrayOf(ax, ay, az),
                    forwardUnit = floatArrayOf(fx, fy, fz),
                    gravity     = gravityVec
                )

// clamp spikes like longitudinal
                val latRaw = lat.coerceIn(-G_CLAMP, G_CLAMP)

// EMA using the same alpha as longitudinal
                val emaPrevLat = emaLatG ?: latRaw
                val emaNowLat  = emaPrevLat + alpha * (latRaw - emaPrevLat)
                emaLatG = emaNowLat

// deadband around zero for stability
                val latSmooth = if (kotlin.math.abs(emaNowLat) < DEAD_BAND_G) 0f else emaNowLat

// publish smoothed lateral g
                latest.value = latest.value.copy(latG = latSmooth)



// (next step we'll store this in LatestInputs and show it in the UI)


            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

// STEP 10b-fix: get the gravity sensor
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)

// existing linear accel registration
        sm.registerListener(listener, lin, SensorManager.SENSOR_DELAY_GAME)

// STEP 10c: start receiving gravity updates
        sm.registerListener(gravityListener, grav, SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            sm.unregisterListener(listener)
            sm.unregisterListener(gravityListener) // also unregister gravity
        }

    }
}

// Hold one GPS reading (latitude, longitude) with a monotonic time tag (tMillis).
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val tMillis: Long  // from android.os.SystemClock.elapsedRealtime()
)

 //LatestInputs holds the most recent sensor readings we care about.
data class LatestInputs(
    val gps: GpsFix? = null,
    val longG: Float? = null,      // longitudinal accel in g, projected on calibrated forward
    val latG:  Float? = null,
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

    // --- Countdown (time-to-brake) ---
    val countdownShow: Boolean = false,
    val countdownSeconds: Int? = null,        // integer seconds to display (3, 2, 1)
    // val countdownRingFrac: Float? = null,     // 0..1 for ring progress

// helpers to compute t->brake
    val lastGpsFix: GpsFix? = null,           // for speed calc
    val prevDistToFastestBP: Double? = null,   // to check we’re actually approaching

    //allow 0 to be displayed past brake point

    val countdownHoldUntilMs: Long? = null

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

private const val COUNTDOWN_WARN_TIME_S = 6.0


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
/*
@android.annotation.SuppressLint("MissingPermission") //supress lint warnings for location permission
@Composable
private fun GpsProducer(latest: MutableState<LatestInputs>) {
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
                latest.value = latest.value.copy(
                    gps = GpsFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        tMillis = android.os.SystemClock.elapsedRealtime()
                    )
                )
            }
        }

        fused.requestLocationUpdates(req, cb, ctx.mainLooper)
        onDispose { fused.removeLocationUpdates(cb) }
    }
}

*/

@Composable
fun rememberLatestInputsMailbox(): MutableState<LatestInputs> {
    return remember { mutableStateOf(LatestInputs()) }
}


// allows swipe between a "Racing UI — coming soon" page and the live Debug page.

@Composable
fun RacingScreen() {
    // 10 Hz heartbeat counter
    var ticks by remember { mutableStateOf(0L) }


    // --- Track selection from DataStore ---
    val context = LocalContext.current
    val usbSource = remember { com.example.hotlapmobile.util.UsbPuckGpsSource(context) }  // 👇 NEW: single shared USB GPS source for the whole screen
    val trackRepo = remember(context) { TrackRepo(context) }
    val selectedTrack = trackRepo.current.collectAsStateWithLifecycle(initialValue = null).value
    if (selectedTrack == null) {
        // Optional: simple loading stub
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading track…")
        }
        return
    }


    // start/stop USB once for the whole screen
    DisposableEffect(Unit) {
        usbSource.start()
        onDispose { usbSource.stop() }
    }
    // Mailbox for latest sensor inputs
    val latest = rememberLatestInputsMailbox()
    val world = rememberWorldState()

    // ⬇⬇ THIS IS CRITICAL ⬇⬇
    GpsUsbProducer(
        latest = latest,
        source = usbSource
    )


    val track = selectedTrack
    LaunchedEffect(track.name) {
        world.value = world.value.resetForTrack(track)
    }



    //context for braking and accelerating
    val prefsRepo = remember(context) { PrefsRepo(context) }
    val brakeThreshG = prefsRepo.brakeThreshG
        .collectAsStateWithLifecycle(initialValue = 0.20f) // default if unset
        .value
    var phase by remember { mutableStateOf(DrivePhase.UNKNOWN) }


    // start streaming linear acceleration → latest.value.longG
    AccelProducer(latest)

    // Start GPS producer
    //GpsProducer(latest)


// Show a tiny status readout somewhere
    UsbPuckDebugPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        source = usbSource
    )


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
            // 4) Update countdown (needs fastestBrakePts, GPS history, and corner targeting)
            world.value = updateCountdownState(latest.value, world.value, track)

            // 5) Try capturing a candidate brake point (tidy one-liner)
            world.value = updateBrakePointState(world.value, track)



            // 5) Any other per-tick bookkeeping you keep (optional)
            // phase = phaseNow   // if you show it elsewhere
            ticks++

            delay(100L)
        }
    }

    // ---- UI: two pages
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> RacingUi(world = world.value, track = track, g = latest.value.longG ?: 0f, latG = latest.value.latG)
            1 -> DebugUi(ticks = ticks, latest = latest.value, world = world.value, track = track, phase = phase, brakeThreshG = brakeThreshG)
        }
    }

}


/*
 * RacingUi: placeholder for the real racing HUD.
 *
 * Purpose:
 * - Placeholder only; we'll build the countdown UI later.
 */

private fun countdownColor(sec: Int?): Color {
    return when (sec) {
        6, 5, 4 -> Color(0xFF00C853) // green
        3, 2, 1 -> Color(0xFFFFEB3B) // yellow
        0       -> Color(0xFFFF1744) // red
        else    -> Color.Gray         // inactive/default
    }
}
@Composable
private fun RacingUi(world: WorldState, track: Track, g: Float, latG: Float? = null) {



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



// COUNTDOWN HUD
        val show = world.countdownShow
        val sec  = world.countdownSeconds


// Center: ring with countdown text on top + lateral bar below
        Box(Modifier.align(Alignment.Center)) {
            val show = world.countdownShow
            val sec  = world.countdownSeconds
            val color = countdownColor(sec)
            var lateralG by remember { mutableStateOf(0.4f) }  // start with a visible fake value

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // STEP 6b: ring + text layered together
                Box(contentAlignment = Alignment.Center) {
                    CountdownRing(size = 400.dp, strokeDp = 36.dp, color = color)
                    if (show && sec != null) {
                    //if (true) {
                        Text(
                            text = sec.toString(),
                            color = color,
                            //text = 5.toString(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 350.sp
                        )
                    }
                    Box( modifier = Modifier .align(Alignment.CenterEnd) .padding(end = 12.dp) ) {
                        RightGIndicator(g = g, maxAbs = 0.5f)
                    }

                }

                Spacer(Modifier.height(1.dp))
                // STEP 6c: Show the lateral G bar under the ring (fake value for now)
                LateralGBar(valueG = (latG ?: 0f), modifier = Modifier.width(320.dp))


            }
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

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Text("DEBUG V1.1", fontSize = 22.sp)
        Text("Track: ${track.name}  (corners=${track.corners.size})")
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




        Text("Fastest brake pt for target? " +
                if (world.fastestBrakePts.containsKey(world.targetCornerIdx)) "yes" else "no")
        Text("Last capture: ${world.lastBrakeCaptureNote ?: "--"}")

        Text("Target corner: ${world.targetCornerIdx+1}")
        Text("Dist to target: ${world.distToTargetCornerM?.let { "%.1f m".format(it) } ?: "--"}")
        Text("At corner: ${world.atCorner}")

// --- New brake-point debug ---
        val dFast = distToTargetFastestBrakePoint(world)?.let { "%.1f m".format(it) } ?: "--"
        Text("Dist to target brake pt: $dFast")
        Text("Last capture: ${world.lastBrakeCaptureNote ?: "--"}")


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

//Rising-edge latch to increment lapCount at Start/Finish.
private fun updateLapOnStartZone(world: WorldState): WorldState {
    val now = android.os.SystemClock.elapsedRealtime()
    val allowed = isEnteringAllowed(world, now)
    val newLap = if (allowed) world.lapCount + 1 else world.lapCount
    val newLast = if (allowed) now else world.lastSfEnterMs

    // Always advance the latch so rising-edge detection works next tick
    return world.copy(
        lapCount = newLap,
        wasInStartZone = world.inStartZone,
        lastSfEnterMs = newLast
    )
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


// Finish a lap on subsequent Start/Finish crossings.
private fun finishLapOnCrossing(world: WorldState): WorldState {
    val start = world.currentLapStartMs ?: return world
    val now = android.os.SystemClock.elapsedRealtime()
    if (!isEnteringAllowed(world, now)) return world

    val lapMs = now - start
    val wasBest = (world.bestLapMs == null) || (lapMs < world.bestLapMs!!)
    val newBest = world.bestLapMs?.let { kotlin.math.min(it, lapMs) } ?: lapMs

    // rollover timing to next lap
    val rolled = world.copy(
        bestLapMs = newBest,
        currentLapStartMs = now,      // immediately start next lap
        currentLapElapsedMs = 0L,
        lastSfEnterMs = now
    )

    // promote brake candidates on PB; always clear candidates after a lap
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

// function to check if we're at the start finish and update the world state accordingly
private fun checkIfAtStartFinish(
    inputs: LatestInputs,
    world: WorldState,
    track: Track
): WorldState {
    var w = world
    // 1) compute inStartZone / dist
    w = updateStartZone(inputs, w, track)
    // 2) start timing on the very first valid crossing (must happen BEFORE we latch)
    w = startLapOnFirstCrossing(w)
    // 3) finish+roll to next lap on subsequent crossings
    w = finishLapOnCrossing(w)
    // 4) now latch the rising edge (updates wasInStartZone / lastSfEnterMs if allowed)
    w = updateLapOnStartZone(w)
    // 5) update the elapsed display
    w = tickUpdateLapElapsed(w)
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



private fun updateCountdownState(
    latest: LatestInputs,
    world: WorldState,
    track: Track
): WorldState {
    // --- HOLD: if we're within the hold window, force "0" visible and skip the rest ---
    val nowMs = SystemClock.elapsedRealtime()
    world.countdownHoldUntilMs?.let { holdUntil ->
        if (nowMs < holdUntil) {
            return world.copy(
                countdownShow = true,
                countdownSeconds = 0
            )
        }
    }



// Inside your countdown updater function, returning a new World
    val fix = latest.gps
    if (fix == null) {
        // No GPS this tick → hide countdown but keep other state
        return world.copy(
            countdownShow = false,
            countdownSeconds = null
        )
    }

// If there isn't a *new* GPS fix, keep what we're showing
    world.lastGpsFix?.let { last ->
        if (fix.tMillis == last.tMillis) {
            return world // preserve countdownShow / countdownSeconds unchanged
        }
    }

// ---- from here on we know we have a NEW fix ----

// (example) compute distance to target brake point
    val distNow = distToTargetFastestBrakePoint(world) ?: return world.copy(
        lastGpsFix = fix,
        countdownShow = false,
        countdownSeconds = null
    )

// (example) compute speed from last fix, if you keep that around
    val last = world.lastGpsFix
    val dtSec = if (last != null) ((fix.tMillis - last.tMillis).coerceAtLeast(1)) / 1000.0 else null
    val dMeters = if (last != null) haversineMeters(last.lat, last.lon, fix.lat, fix.lon) else null
    val speedMps = if (dtSec != null && dtSec > 0 && dMeters != null) dMeters / dtSec else null

// approaching with a small epsilon to avoid toggle on jitter
    val epsilonM = 0.5
    val approaching = world.prevDistToFastestBP?.let { prev -> distNow <= prev + epsilonM } ?: false

// time-to-brake → countdown decision
    val tToBrake = speedMps?.let { if (it > 0.1) distNow / it else Double.POSITIVE_INFINITY }
        ?: Double.POSITIVE_INFINITY
    val out = countdownFrom(tToBrake, COUNTDOWN_WARN_TIME_S)



    val newShow = out.show && approaching
    val newSec  = if (newShow) out.secondsInt else null

    // If we just transitioned to 0, start the hold window
    val prevSec = world.countdownSeconds
    val startHoldNow = (newShow && newSec == 0 && prevSec != 0)
    val holdUntil = if (startHoldNow) nowMs + COUNTDOWN_HOLD_MS else world.countdownHoldUntilMs


    return world.copy(
        lastGpsFix = fix,
        prevDistToFastestBP = distNow,
        countdownShow = newShow,
        countdownSeconds = newSec,
        countdownHoldUntilMs = holdUntil
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
            .width(40.dp)
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

// STEP 7: Replace LateralGBar with version that fills from center left/right
@Composable
fun LateralGBar(
    valueG: Float = 0f,          // current lateral g (− = left, + = right)
    maxAbsG: Float = 1.5f,       // full-scale range in g’s
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Gray),   // gray background
        contentAlignment = Alignment.Center
    ) {
        // --- draw green fill from screen center
        Canvas(modifier = Modifier.matchParentSize()) {
            val clamped = valueG.coerceIn(-maxAbsG, maxAbsG)
            val frac = kotlin.math.abs(clamped) / maxAbsG
            val cx = size.width / 2f
            val w = size.width * frac
            val h = size.height

            if (clamped > 0f) {
                // positive → fill to the right
                drawRect(
                    color = Color(0xFF22C55E),
                    topLeft = Offset(cx, 0f),
                    size = Size(w, h)
                )
            } else if (clamped < 0f) {
                // negative → fill to the left
                drawRect(
                    color = Color(0xFF22C55E),
                    topLeft = Offset(cx - w, 0f),
                    size = Size(w, h)
                )
            }
        }

        // --- centered text readout on top
        Text(
            text = String.format("%.1f g", valueG),
            color = Color.White,
            fontSize = 18.sp
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

private data class CountdownOut(
    val show: Boolean,
    val secondsInt: Int = 0,

)

/** Convert time-to-brake (seconds) into UI state given a warn window. */
private fun countdownFrom(tToBrake: Double, warnTimeS: Double): CountdownOut {
    if (tToBrake.isNaN() || tToBrake.isInfinite()) return CountdownOut(false)
    // allow small negative (crossed exactly now); hide if too far past or too early
    if (tToBrake < -0.25 || tToBrake > warnTimeS) return CountdownOut(false)
    val sec = max(0.0, floor(tToBrake)).toInt()   // clamp to 0 (so we can show “0”)

    return CountdownOut(true, sec)
}

/** Distance from current fix to the FASTEST brake point of the target corner. */
private fun distToTargetFastestBrakePoint(world: WorldState): Double? {
    val fix = world.currentLatLon ?: return null
    val bp  = world.fastestBrakePts[world.targetCornerIdx] ?: return null
    return haversineMeters(fix.lat, fix.lon, bp.lat, bp.lon)
}

private fun WorldState.resetForTrack(track: Track) = copy(
    // ensure target points at a valid corner
    targetCornerIdx = 0,

    // clear per-track, per-corner things so we don’t mix tracks
    candidateBrakePts = emptyMap(),
    fastestBrakePts = emptyMap(),

    // clear countdown approach history so we don’t “approach” an old BP
    prevDistToFastestBP = null,
    lastGpsFix = null,
    countdownShow = false,
    countdownSeconds = null,


    // (optional) reset lap timing if you want laps to be per-track
    lapCount = 0,
    currentLapStartMs = null,
    currentLapElapsedMs = null,
    bestLapMs = null,
    lastSfEnterMs = null
)
