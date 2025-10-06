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


// Hold one GPS reading (latitude, longitude) with a monotonic time tag (tMillis).
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val tMillis: Long  // from android.os.SystemClock.elapsedRealtime()
)

 //LatestInputs holds the most recent sensor readings we care about.
data class LatestInputs(
    val gps: GpsFix? = null
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

    // Start GPS producer
    GpsProducer(latest)

    // 10 Hz loop (runs in background coroutine)
    LaunchedEffect(Unit) {
        while (true) {
            world.value = checkIfAtStartFinish(latest.value, world.value, track)
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
            0 -> RacingUi(world = world.value, track = track)
            1 -> DebugUi(ticks = ticks, latest = latest.value, world = world.value, track = track)
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
private fun RacingUi(world: WorldState, track: Track) {
    Box(modifier = Modifier.fillMaxSize()) {

        // TOP: Track name (small, out of the way)
        Text(
            text = track.name,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )

        // CENTER: Big current lap time + best lap
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Current lap time — as big as is practical for most devices
            Text(
                text = "Current Lap: ${formatMs(world.currentLapElapsedMs)}",
                fontSize = 36.sp    // bump up/down after a road test if needed
            )

            // Best lap (secondary, but still large)
            Text(
                text = "Best: ${formatMs(world.bestLapMs)}",
                fontSize = 36.sp
            )
        }

        // BOTTOM: Lap counter
        Text(
            text = "Lap: ${world.lapCount}",
            fontSize = 26.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
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
private fun DebugUi(ticks: Long, latest: LatestInputs, world: WorldState, track: Track) {
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
