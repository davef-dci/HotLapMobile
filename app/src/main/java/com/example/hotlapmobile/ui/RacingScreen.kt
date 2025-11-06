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

//imports for settings

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.data.SettingsRepo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log




enum class DrivePhase { BRAKING, COASTING, ACCELERATING, UNKNOWN }


// STEP 8: Dot product of two 3D vectors (utility for projections)
private fun dot(a: FloatArray, b: FloatArray): Float =
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

// STEP 8: Cross product of two 3D vectors (utility to compute lateral axis)
private fun cross(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

// STEP 8: Normalize a 3D vector (utility to get unit vectors)
private fun normalize(v: FloatArray): FloatArray {
    val m = kotlin.math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
    return if (m == 0f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(v[0] / m, v[1] / m, v[2] / m)
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


@Composable
private fun SegmentedCountdownRing(
    segmentsColored: Int,            // 4..0
    size: Dp = 400.dp,
    strokeDp: Dp = 36.dp,
    activeColor: Color,
    inactiveColor: Color = Color(0xFF9CA3AF) // gray
) {
    Canvas(Modifier.size(size)) {
        val stroke = strokeDp.toPx()
        val diameter = size.toPx()
        val arcSize = Size(diameter - stroke, diameter - stroke)
        val topLeft = Offset(stroke / 2, stroke / 2)

        // Draw 4 segments: 0–90, 90–180, 180–270, 270–360 (clockwise)
        // Segment i is colored if i < segmentsColored, else gray.
        repeat(4) { i ->
            val start = i * 90f
            val color = if (i < segmentsColored) activeColor else inactiveColor
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
                topLeft = topLeft,
                size = arcSize
            )
        }
    }
}


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
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).let { if (it == 0f) 1f else it }
        Triple(v[0] / n, v[1] / n, v[2] / n)
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
                    linearAcc = floatArrayOf(ax, ay, az),
                    forwardUnit = floatArrayOf(fx, fy, fz),
                    gravity = gravityVec
                )

// clamp spikes like longitudinal
                val latRaw = lat.coerceIn(-G_CLAMP, G_CLAMP)

// EMA using the same alpha as longitudinal
                val emaPrevLat = emaLatG ?: latRaw
                val emaNowLat = emaPrevLat + alpha * (latRaw - emaPrevLat)
                emaLatG = emaNowLat

// deadband around zero for stability
                val latSmooth = if (kotlin.math.abs(emaNowLat) < DEAD_BAND_G) 0f else emaNowLat

// publish smoothed lateral g
                latest.value = latest.value.copy(latG = latSmooth)
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
    val latG: Float? = null,
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

    val countdownHoldUntilMs: Long? = null,

    val trackMarker: Int? = null,          // 6..1 when approaching brake point, null when off
    val markerQuarter: Int? = null,        // 4..0 segments colored for the current marker
    val approachingBrakePt: Boolean = false,
    val zeroHoldCornerIdx: Int? = null,  // NEW: latch “0” until we rotate to the next corner
    val prevApproaching: Boolean? = null,        // last tick’s approaching flag
    val leavingStreak: Int = 0                  // debounce: consecutive “leaving” samples

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


@Composable
fun rememberLatestInputsMailbox(): MutableState<LatestInputs> {
    return remember { mutableStateOf(LatestInputs()) }
}

// allows swipe between a racing UI page and the live Debug page.

@Composable
fun RacingScreen() {
    // 10 Hz heartbeat counter
    var ticks by remember { mutableStateOf(0L) }

    // --- Track selection from DataStore ---
    val context = LocalContext.current
    val usbSource =
        remember { com.example.hotlapmobile.util.UsbPuckGpsSource(context) }  // 👇 NEW: single shared USB GPS source for the whole screen
    val trackRepo = remember(context) { TrackRepo(context) }
    val selectedTrack = trackRepo.current.collectAsStateWithLifecycle(initialValue = null).value

    val settingsRepo = remember(context) { SettingsRepo(context) }
    val globalSettings = settingsRepo.settings
        .collectAsStateWithLifecycle(
            initialValue = com.example.hotlapmobile.config.GlobalSettingsDefaults.default
        ).value

    val cornerToleranceM = globalSettings.cornerToleranceM
    val brakeZoneDistanceM = globalSettings.brakeZoneDistanceM
    val brakeWarnDistanceM = globalSettings.brakeWarnDistanceM




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
    // Ensure the loop always sees the latest value even if LaunchedEffect was keyed to Unit
    val currentThresh by rememberUpdatedState(brakeThreshG)
    var phase by remember { mutableStateOf(DrivePhase.UNKNOWN) }

    // start streaming linear acceleration → latest.value.longG
    AccelProducer(latest)

/* Show a tiny status readout
    UsbPuckDebugPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        source = usbSource
    )
*/

    LaunchedEffect(Unit) {
        while (true) {

            // Update start/finish and corner targeting based on current GPS position
            world.value = checkIfAtStartFinish(latest.value, world.value, track)
            world.value = updateCornerState(latest.value, world.value, track, cornerToleranceM)

            val threshNow = currentThresh         // <-- fresh, not captured


            // Determine the current driving phase (e.g., braking, accelerating, steady)
            Log.d("PHASE", "longG=${latest.value.longG}, thresh=$threshNow")

            val phaseNow = detectDrivePhase(latest.value.longG, threshNow)

            phase = phaseNow  // keep this live so UI updates

            // Refresh the world state with the latest GPS position and braking status
            val fix = latest.value.gps
            world.value = world.value.copy(
                currentLatLon = fix?.let { LatLon(it.lat, it.lon) },
                isBrakingNow = (phaseNow == DrivePhase.BRAKING)
            )

            // Update brake-point capture logic (records candidate brake points for each corner)
            world.value = updateBrakePointState(
                world = world.value,
                track = track,
                brakeZoneDistanceM = brakeZoneDistanceM
            )

            // Normalize approach state (helps detect when the driver is approaching or leaving a corner)
            world.value = updateApproachState(world.value)

            // Update the track marker display (Lua-style distance countdown to braking point)
            world.value = updateTrackMarkerState(world.value, brakeWarnDistanceM)

            // Increment tick counter and delay until next update
            ticks++
            delay(100L)
        }
    }


    // ---- UI: two pages
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> RacingUi(
                world = world.value,
                track = track,
                g = latest.value.longG ?: 0f,
                latG = latest.value.latG
            )

            1 -> GGUi(
                maxAbsG = globalSettings.ggMaxAbsG.toFloat(),
                latG = latest.value.latG ?: 0f,     // X-axis
                longG = latest.value.longG ?: 0f,   // Y-axis
                trailSeconds = globalSettings.ggTrailSeconds.toFloat(), // NEW
                ticks = ticks,                                           // NEW (sample @ 10 Hz)
                brakeThreshG = brakeThreshG                             // ← NEW
            )


            2 -> DebugUi(
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


/*
Countdown UI
 */

private fun countdownColor(sec: Int?): Color {
    return when (sec) {
        6, 5, 4 -> Color(0xFF00C853) // green
        3, 2, 1 -> Color(0xFFFFEB3B) // yellow
        0 -> Color(0xFFFF1744) // red
        else -> Color.Gray         // inactive/default
    }
}

@Composable
private fun RacingUi(world: WorldState, track: Track, g: Float, latG: Float? = null) {

    Box(modifier = Modifier.fillMaxSize()) {

        // Display the track name at the top
        Text(
            text = track.name,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )

        // Display the row of brake point dots below the track name
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        ) {
            BrakePointDots(track = track, world = world)
        }

        // Center section: large distance marker ring and indicators
        Box(Modifier.align(Alignment.Center)) {

            val marker = world.trackMarker           // Current marker value (1–6) or null
            val approaching = world.approachingBrakePt
            val color = markerColor(marker)          // Marker color based on current state

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {

                    // Outer countdown ring showing approach intensity
                    //CountdownRing(size = 400.dp, strokeDp = 36.dp, color = color)

                    val colored = world.markerQuarter ?: 0
                    val color = markerColor(marker)

// Segmented ring
                    SegmentedCountdownRing(
                        segmentsColored = colored,
                        size = 400.dp,
                        strokeDp = 36.dp,
                        activeColor = color
                    )

// Big numeric marker on top
                    marker?.let {
                        Text(
                            text = it.toString(),
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 350.sp
                        )
                    }



                    // Large numeric marker displayed when approaching and valid
                    val marker = world.trackMarker  // Int?
                    marker?.let {
                        Text(
                            text = it.toString(),
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 350.sp
                        )
                    }



                    // Longitudinal G-force indicator displayed on the right side of the ring
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                    ) {
                        RightGIndicator(g = g, maxAbs = 0.5f)
                    }
                }

                Spacer(Modifier.height(1.dp))

                // Lateral G-force bar shown below the ring
                LateralGBar(valueG = (latG ?: 0f), modifier = Modifier.width(320.dp))
            }
        }

        // Bottom section: lap information and timing stats
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Target Corner: ${world.targetCornerIdx + 1}", fontSize = 32.sp)
            Text(text = "Current Lap: ${formatMs(world.currentLapElapsedMs)}", fontSize = 32.sp)
            Text(text = "Best: ${formatMs(world.bestLapMs)}", fontSize = 28.sp)
            Text(text = "Lap: ${world.lapCount}", fontSize = 22.sp)
        }
    }
}


@Composable
fun GGUi(
    maxAbsG: Float,
    latG: Float,
    longG: Float,
    trailSeconds: Float,
    ticks: Long,
    brakeThreshG: Float,
    modifier: Modifier = Modifier
) {
    // Rolling trail of recent samples (lat, long, tMillis)
    data class TrailPt(val x: Float, val y: Float, val t: Long)
    val trail = remember { mutableStateListOf<TrailPt>() }

    // Sample once per 10 Hz tick
    LaunchedEffect(ticks) {
        val now = android.os.SystemClock.elapsedRealtime()
        trail.add(TrailPt(latG, longG, now))

        // Prune anything older than the window; also cap size defensively
        val windowMs = (trailSeconds.coerceAtLeast(0.2f) * 1000f).toLong()
        val cutoff = now - windowMs




        while (trail.isNotEmpty() && trail.first().t < cutoff) trail.removeAt(0)
        if (trail.size > 400) { // hard cap ~40s at 10 Hz, just in case
            trail.removeRange(0, trail.size - 400)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
// ---- BIG READOUT HUD (top-left)
        val accelColor = phaseColorFor(longG, brakeThreshG)
        val trPercent = trailBrakingRatio(latG, longG)

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Longitudinal Accel (g) — color by braking/accelerating
            Text(
                text = "Long Accel: ${"%.2f".format(longG)} g",
                color = accelColor,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )

            // Lateral Accel (g) — show value big; keep neutral color (white) for legibility
            Text(
                text = "Lat Accel:  ${"%.2f".format(latG)} g",
                color = Color(0xFF60A5FA),        // bright blue for contrast
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )

            // Trail Braking Ratio (%) — big, neutral; show “—” if undefined
            Text(
                text = "Trail Brake %: ${trPercent?.let { "$it%" } ?: "—"}",
                color = Color(0xFFFACC15),        // bright yellow for readability
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
        }


        /*
        // HUD: show scale + current g's
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
        ) {
            Text("G-G scale: ±${"%.3f".format(maxAbsG)} g")
            Text("lat: ${"%.3f".format(latG)} g   long: ${"%.3f".format(longG)} g")
            Text("trail: ${"%.1f".format(trailSeconds)} s")
        }

         */

        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension * 0.5f

            // Static axes
            drawCircle(Color.LightGray, radius, Offset(cx, cy), style = Stroke(3f))
            drawLine(Color.LightGray, Offset(cx - radius, cy), Offset(cx + radius, cy), 2f)
            drawLine(Color.LightGray, Offset(cx, cy - radius), Offset(cx, cy + radius), 2f)

            fun toPx(g: Float) = (g / maxAbsG) * radius

            // Helper to clamp a point to the circle edge (preserves saturation cue)
            fun clampToCircle(xIn: Float, yIn: Float): Offset {
                var x = xIn
                var y = yIn
                val dx = x - cx
                val dy = y - cy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > radius && dist > 0f) {
                    val s = radius / dist
                    x = cx + dx * s
                    y = cy + dy * s
                }
                return Offset(x, y)
            }


// --- NEW: render fading line segments between trail points (oldest → newest)
            val now = android.os.SystemClock.elapsedRealtime()
            val windowMs = (trailSeconds.coerceAtLeast(0.2f) * 1000f).toLong()

            if (trail.isNotEmpty()) {
                data class RenderPt(val p: Offset, val alpha: Float)
                val renders = buildList<RenderPt> {
                    for (pt in trail) {
                        val age = (now - pt.t).coerceAtLeast(0)
                        val frac = 1f - (age.toFloat() / windowMs.toFloat()) // 1 → 0 with age
                        if (frac <= 0f) continue
                        val alpha = (frac * frac).coerceIn(0f, 1f)
                        val px = cx + toPx(pt.x)
                        val py = cy - toPx(pt.y)
                        val c = clampToCircle(px, py)
                        add(RenderPt(c, alpha))
                    }
                }

                for (i in 1 until renders.size) {
                    val a = renders[i - 1]
                    val b = renders[i]
                    val segAlpha = kotlin.math.min(a.alpha, b.alpha)
                    if (segAlpha > 0f) {
                        drawLine(
                            color = if (b.p.y > cy) Color.Red.copy(alpha = segAlpha) else Color.Green.copy(alpha = segAlpha),

                            start = a.p,
                            end = b.p,
                            strokeWidth = 10f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }







            if (trail.isNotEmpty()) {
                for (pt in trail) {
                    val age = (now - pt.t).coerceAtLeast(0)
                    val frac = 1f - (age.toFloat() / windowMs.toFloat()) // 1 → 0
                    if (frac <= 0f) continue
                    // ease the fade so older dots get faint smoothly
                    val alpha = (frac * frac).coerceIn(0f, 1f)

                    // Map to pixels and clamp to rim
                    val px = cx + toPx(pt.x)
                    val py = cy - toPx(pt.y)
                    val c = clampToCircle(px, py)

                    // Slightly smaller radius for trail points
                    drawCircle(
                        color = Color(0xFF1E88E5).copy(alpha = alpha),
                        radius = 12f,
                        center = c
                    )
                }
            }

            // Draw the current (latest) dot on top, a tad larger/opaque
            run {
                val px = cx + toPx(latG)
                val py = cy - toPx(longG)
                val c = clampToCircle(px, py)
                drawCircle(color = Color(0xFF1E88E5), radius = 15f, center = c)
            }
        }
    }
}




/*
 * DebugUi: live debug panel showing internal values.
 *
 * Shows: track info, ticks/GPS snapshot, start/finish state, lap timing,
 * drive phase, brake-point targeting, and zero-display marker state.
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

    val sectionTitle = @Composable { title: String ->
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
    }

    val line = @Composable { label: String, value: String ->
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("DEBUG V1.3", style = MaterialTheme.typography.headlineSmall)
        line("Track", "${track.name}  (corners=${track.corners.size})")
        line("Tick", ticks.toString())

        Divider()

        sectionTitle("GPS")
        line("Lat", gps?.lat?.toString() ?: "—")
        line("Lon", gps?.lon?.toString() ?: "—")
        line("Age (ms)", ageMs?.toString() ?: "n/a")

        Divider()

        sectionTitle("Start/Finish")
        val dSf = world.distToStartM?.let { String.format("%.1f m", it) } ?: "n/a"
        line("Distance to S/F", dSf)
        line("In S/F zone", world.inStartZone.toString())

        Divider()

        sectionTitle("Laps")
        line("Lap #", world.lapCount.toString())
        line("Current lap", formatMs(world.currentLapElapsedMs))
        line("Best lap", formatMs(world.bestLapMs))

        Divider()

        sectionTitle("Drive Phase")
        val longG = latest.longG ?: 0f
        Text(
            text = buildString {
                appendLine("Phase: ${phase.name}")
                appendLine("Longitudinal accel: ${"%.2f".format(longG)} g")
                append("Threshold: ±${"%.2f".format(brakeThreshG)} g")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            fontSize = 16.sp
        )

        Divider()

        sectionTitle("Brake Point Targeting")
        line(
            "Fastest brake pt for target?",
            if (world.fastestBrakePts.containsKey(world.targetCornerIdx)) "yes" else "no"
        )
        line("Last capture", world.lastBrakeCaptureNote ?: "—")
        line("Target corner", (world.targetCornerIdx + 1).toString())
        line(
            "Dist to target corner",
            world.distToTargetCornerM?.let { "%.1f m".format(it) } ?: "—"
        )
        line("At corner", world.atCorner.toString())

        val distFast = distToTargetFastestBrakePoint(world)?.let { "%.1f m".format(it) } ?: "—"
        line("Dist to target brake pt", distFast)

        Divider()

        // --- NEW: Brake Marker State (Zero Display Debug) ---
        sectionTitle("Brake Marker State")
        line("trackMarker", world.trackMarker?.toString() ?: "—")
        line("approachingBrakePt", world.approachingBrakePt.toString())
        line("zeroHoldCornerIdx", world.zeroHoldCornerIdx?.toString() ?: "—")
        line("prevApproaching", world.prevApproaching?.toString() ?: "—")
        line("leavingStreak", world.leavingStreak.toString())
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
    val millis = ms % 1_000
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
private fun isEnteringAllowed(
    world: WorldState,
    now: Long = android.os.SystemClock.elapsedRealtime()
): Boolean {
    val entering = world.inStartZone && !world.wasInStartZone
    if (!entering) return false
    val last = world.lastSfEnterMs ?: return true
    return (now - last) >= COOLDOWN_MS
}

//update the corner
private fun updateCornerState(
    latest: LatestInputs,
    world: WorldState,
    track: Track,
    cornerToleranceM: Double

): WorldState {
    val fix = latest.gps ?: return world  // no GPS yet

    val (atC, nextIdx, distM) = checkIfAtCornerLua(
        track = track,
        lat = fix.lat,
        lon = fix.lon,
        atCorner = world.atCorner,
        targetCornerIdx = world.targetCornerIdx,
        cornerToleranceM = cornerToleranceM
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
    val newSec = if (newShow) out.secondsInt else null

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

// Distance-based brake marker modeled after the Lua "track_marker" logic.
// Ignores time and speed—only considers how far inside the fixed warning
// distance we are relative to the fastest brake point for the target corner.
// Purely reads state; does not modify approach or previous-distance values.
// The latch to zero is set in updateApproachState() during the approach→leaving transition.
private fun updateTrackMarkerState(
    world: WorldState,
    brakeWarnM: Double,
): WorldState {

    val distNowM = distToTargetFastestBrakePoint(world)
        ?: return world.copy(trackMarker = null, markerQuarter = null)

    // 1) If outside warning, nothing is shown.
    if (distNowM > brakeWarnM) {
        return world.copy(trackMarker = null, markerQuarter = null)
    }

    // 2) If we’re leaving and this corner is latched, show 0 (still in warning).
    val leaving = !world.approachingBrakePt
    val latchedThisCorner = (world.zeroHoldCornerIdx == world.targetCornerIdx)
    if (leaving && latchedThisCorner) {
        return world.copy(trackMarker = 0, markerQuarter = 0)
    }

    // 3) Otherwise, we’re approaching inside warning → 6..1
    val increments = 6
    val frac = (distNowM / brakeWarnM).coerceIn(0.0, 1.0) // 1 = far edge, 0 = at BP

    val raw = distNowM / brakeWarnM * increments
    val marker = kotlin.math.ceil(raw).toInt().coerceIn(1, increments)

    // Progress INSIDE the current marker band (0 at band start → 1 at band end)
    val bandSize = 1.0 / increments
    val bandStart = (marker - 1) * bandSize
    val localProgress = ((bandStart + bandSize) - frac) / bandSize
    // localProgress: 0, .25, .50, .75, 1.00 → quarter steps

    // Colored segments remaining (4..0), graying 1→4 clockwise as progress grows
    val coloredSegments = (4 - kotlin.math.floor(localProgress * 4.0)).toInt()
        .coerceIn(0, 4)

    return world.copy(trackMarker = marker, markerQuarter = coloredSegments)
}



private fun checkIfAtCornerLua(
    track: Track,
    lat: Double,
    lon: Double,
    atCorner: Boolean,
    cornerToleranceM: Double,
    targetCornerIdx: Int // 0-based
): Triple<Boolean, Int, Double> {

    val tol = cornerToleranceM
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
 //   val t = if (threshold.isFinite() && threshold > 0f) threshold else 0.1f
    val t = threshold
    val g = longG ?: return DrivePhase.COASTING  // default instead of UNKNOWN

    return when {
        g <= -t -> DrivePhase.BRAKING
        g >=  t -> DrivePhase.ACCELERATING
        else    -> DrivePhase.COASTING
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
            clamped > deadband -> Color(0xFF16A34A) // green
            clamped < -deadband -> Color(0xFFDC2626) // red
            else -> Color(0xFF9CA3AF) // gray (coast)
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


// Decides whether to capture a brake point and returns the updated WorldState.
private fun updateBrakePointState(
    world: WorldState,
    track: Track,
    brakeZoneDistanceM: Double
): WorldState {
    val fix = world.currentLatLon ?: return world

    val res = updateCandidateBrakePoint(
        targetCornerIdx = world.targetCornerIdx,
        atCorner = world.atCorner,
        distToTargetCornerM = world.distToTargetCornerM,
        phaseIsBraking = world.isBrakingNow,
        brakeZoneDistanceM = brakeZoneDistanceM,
        candidateBrakePts = world.candidateBrakePts,
        current = fix
    )

    return if (
        res.lastBrakeCaptureNote == null &&
        res.candidateBrakePts === world.candidateBrakePts
    ) {
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

// Pick a color for the distance-based marker (Lua-style).
@Composable
private fun markerColor(marker: Int?): Color {
    return when (marker) {
        6,5,4 -> Color(0xFF00C853) // Greenish
        3,2,1 -> Color(0xFFFFA500) // Greenish
        0 -> Color(0xFFFF0000) // Red
        else -> Color.Gray // off / no marker
    }
}


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
    val bp = world.fastestBrakePts[world.targetCornerIdx] ?: return null
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

// Computes 'approachingBrakePt' once per tick and updates prevDistToFastestBP.
// Everyone else should only READ these fields, not write them.
private fun updateApproachState(world: WorldState): WorldState {
    val distNowM = distToTargetFastestBrakePoint(world)
        ?: return world.copy(
            approachingBrakePt = false,
            // do not change prevApproaching / leavingStreak / zeroHoldCornerIdx here
        )

    val prevDist = world.prevDistToFastestBP
    val epsilonM = 0.5  // small noise margin to avoid flicker
    val approaching = prevDist?.let { distNowM <= it + epsilonM } ?: true

    // Debounce “leaving” so we don’t false-trigger on one noisy tick
    val prevApproaching = world.prevApproaching
    val nowLeaving = !approaching
    val leavingStreak = when {
        nowLeaving && (prevApproaching == false || prevApproaching == null) -> world.leavingStreak + 1
        nowLeaving && prevApproaching == true -> 1
        else -> 0
    }

    // Confirmed crossing when we have 2 consecutive "leaving" samples
    val crossedThisTick = (leavingStreak >= 2)

    // Latch zero ONLY if we just crossed and we haven’t latched this corner yet
    val alreadyLatchedThisCorner = world.zeroHoldCornerIdx == world.targetCornerIdx
    val newZeroLatch = if (crossedThisTick && !alreadyLatchedThisCorner) world.targetCornerIdx else world.zeroHoldCornerIdx

    return world.copy(
        approachingBrakePt = approaching,
        prevApproaching = approaching,
        prevDistToFastestBP = distNowM,
        leavingStreak = leavingStreak,
        zeroHoldCornerIdx = newZeroLatch
    )
}


@Composable
private fun phaseColorFor(longG: Float, thresh: Float): Color {
    return when (detectDrivePhase(longG, thresh)) {
        DrivePhase.BRAKING       -> Color(0xFFDC2626)  // red
        DrivePhase.ACCELERATING  -> Color(0xFF16A34A)  // green
        else                     -> Color(0xFF9CA3AF)  // gray
    }
}

/**
 * Trail Braking Ratio (%):
 * 0% = all lateral, 100% = all longitudinal (more intuitive “how much is braking”).
 * Uses magnitudes so sign doesn’t matter.
 */
private fun trailBrakingRatio(latG: Float, longG: Float): Int? {
    val lat = kotlin.math.abs(latG)
    val lon = kotlin.math.abs(longG)
    val sum = lat + lon
    if (sum < 1e-6f) return null
    return ((lon / sum) * 100f).toInt().coerceIn(0, 100)
}
