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




enum class DrivePhase { BRAKING, COASTING, ACCELERATING, UNKNOWN }


//stream the accelerometer sensor and fill longG based on +X axis
@Composable
private fun AccelProducer(latest: MutableState<LatestInputs>) {
    val ctx = LocalContext.current



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


    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (lin == null) {
            // Device has no linear-accel sensor; clear value
            latest.value = latest.value.copy(longG = null)
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                // Proejct acceleation onto the calibrated X axis
                val ax = e.values[0]           // m/s² along +X
                val ay = e.values[1]           // m/s² along +Y
                val az = e.values[2]           // m/s² along +Z

                val projMs2 = fx * ax + fy * ay + fz * az
                val gProj = projMs2 / ONE_G
                latest.value = latest.value.copy(longG = gProj)


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
    val tMillis: Long  // from android.os.SystemClock.elapsedRealtime()
)

 //LatestInputs holds the most recent sensor readings we care about.
data class LatestInputs(
    val gps: GpsFix? = null,
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
    val lastSfEnterMs: Long? = null
)


//helpers to convert sensor readings to g's and project acceleration onto calibrated forward axis.

private const val ONE_G = 9.80665f   // conversion from m/s² → g

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

    //context for braking and accelerating
    val prefsRepo = remember(context) { PrefsRepo(context) }
    val brakeThreshG = prefsRepo.brakeThreshG
        .collectAsStateWithLifecycle(initialValue = 0.20f) // default if unset
        .value
    var phase by remember { mutableStateOf(DrivePhase.UNKNOWN) }


    // start streaming linear acceleration → latest.value.longG
    AccelProducer(latest)

    // Start GPS producer
    GpsProducer(latest)

    // 10 Hz loop (runs in background coroutine)
    LaunchedEffect(Unit) {
        while (true) {
            world.value = checkIfAtStartFinish(latest.value, world.value, track) // check if at start finish

            phase = detectDrivePhase(latest.value.longG, brakeThreshG) // check if accelerating or braking

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
            0 -> RacingUi(world = world.value, track = track, g=latest.value.longG ?: 0f)
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
    }
}

@Composable
private fun GReadoutBox(g: Float, modifier: Modifier = Modifier) {
    val label = "${"%.1f".format(g)} g"

    Box(
        modifier = modifier
            .width(90.dp)            // wider
            .height(120.dp)          // taller gray band
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


//Finish a lap on subsequent Start/Finish crossings.
private fun finishLapOnCrossing(world: WorldState): WorldState {
    val start = world.currentLapStartMs ?: return world
    val now = android.os.SystemClock.elapsedRealtime()
    if (!isEnteringAllowed(world, now)) return world

    val lapMs = now - start
    val newBest = world.bestLapMs?.let { kotlin.math.min(it, lapMs) } ?: lapMs
    return world.copy(
        bestLapMs = newBest,
        currentLapStartMs = now,      // immediately start next lap
        currentLapElapsedMs = 0L,
        lastSfEnterMs = now
    )
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
    track: com.example.hotlapmobile.config.Track
): WorldState {
    var w = world
    w = updateStartZone(inputs, w, track)  // 1
    w = finishLapOnCrossing(w)             // 2
    w = startLapOnFirstCrossing(w)         // 3
    w = updateLapOnStartZone(w)            // 4
    w = tickUpdateLapElapsed(w)            // 5
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
        val frac = abs(clamped) / maxAbs

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
            .width(110.dp)
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
