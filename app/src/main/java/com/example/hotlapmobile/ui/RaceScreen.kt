// app/src/main/java/com/example/hotlapmobile/ui/RaceScreen.kt
package com.example.hotlapmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hotlapmobile.config.LatLon
import com.example.hotlapmobile.config.Track
import com.example.hotlapmobile.config.Tracks
import com.example.hotlapmobile.data.DriverRepo
import com.example.hotlapmobile.ui.race.useTrainingTelemetry
import com.example.hotlapmobile.util.haversineMeters
import kotlin.math.roundToInt

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight

@Composable
fun RaceScreen() {
    val track = Tracks.DcfNeighborhood   // TODO: load selected track from TrackRepo
    val repo = remember { DriverRepo(track) }
    var trainingDone by remember { mutableStateOf(false) }

    if (!trainingDone) {
        TrainingScreen(
            track = track,
            repo = repo,
            onFinished = { trainingDone = true }
        )
    } else {
        RacingScreen(track = track, repo = repo)
    }
}

/* ---------- Racing Screen with BIG countdown + lap info ---------- */


private data class GpsPoint(val lat: Double, val lon: Double)

private class RaceSession(
    private val track: Track,
    private val repo: DriverRepo



) {

    // --- brake latch (hold the BRAKE state for a moment) ---
    private var brakeLatchCorner: Int? = null
    private var brakeLatchUntilMs: Long = 0L

    fun updateBrakeLatch(
        nowMs: Long,
        targetCornerIdx: Int,
        distToBp: Double?,
        cornerToleranceM: Double,
        holdMs: Long = 2000L   // adjust the hold duration here (ms)
    ) {
        // If we enter tolerance for this corner, (re)latch
        if (distToBp != null && distToBp.isFinite() && distToBp <= cornerToleranceM) {
            brakeLatchCorner = targetCornerIdx
            brakeLatchUntilMs = nowMs + holdMs
        }
        // If we’ve moved on and latch expired, clear it
        if (nowMs > brakeLatchUntilMs && brakeLatchCorner != targetCornerIdx) {
            brakeLatchCorner = null
            brakeLatchUntilMs = 0L
        }
    }

    fun isBrakeLatched(nowMs: Long, targetCornerIdx: Int): Boolean {
        return (nowMs <= brakeLatchUntilMs) && (brakeLatchCorner == targetCornerIdx)
    }



    // Initialize "fastest" from trained points captured during Training
    private fun fastestFromTraining(): MutableList<GpsPoint?> =
        repo.brakePoints.map { it?.let { p -> GpsPoint(p.lat, p.lon) } }.toMutableList()

    val fastest: MutableList<GpsPoint?> = fastestFromTraining()
    val currentLap: MutableList<GpsPoint?> = MutableList(track.corners.size) { null }

    var currentLapNo by mutableStateOf(1)
        private set
    var fastestLapNo by mutableStateOf<Int?>(null)
        private set
    var fastestLapTimeMs by mutableStateOf<Long?>(null)
        private set

    private var lapStartMs = System.currentTimeMillis()
    private var prevTargetCorner: Int? = null
    private var prevAtCorner = false

    // ---- approach/leave tracking ----
    private var lastTargetForDist: Int? = null
    private var d1: Double? = null
    private var d2: Double? = null
    private var d3: Double? = null
    private var d4: Double? = null

    /** Call each tick with current dist-to-fastest BP for the current target corner. */
    fun updateApproachHistory(targetCornerIdx: Int, distToBp: Double?) {
        if (lastTargetForDist != targetCornerIdx) {
            // new corner: reset history
            d1 = null; d2 = null; d3 = null; d4 = null
            lastTargetForDist = targetCornerIdx
        }
        if (distToBp != null && distToBp.isFinite()) {
            d4 = d3; d3 = d2; d2 = d1; d1 = distToBp
        }
    }

    /** True if we are closing in vs. moving away. Uses d1 vs d4 to smooth jitter. */
    fun isApproaching(thresholdM: Double = 0.5): Boolean {
        val a = d1; val b = d4
        if (a == null || b == null) return true // default to showing until we have history
        return (b - a) > thresholdM // distance decreased by > threshold
    }


    fun onTick(
        nowMs: Long,
        atCornerNow: Boolean,
        targetCornerIdx: Int,
        brakingNow: Boolean,
        here: LatLon?,
        distanceToTargetCornerM: Double?,
        brakeZoneDistanceM: Double
    ) {
        // Record current-lap brake point on braking EDGE inside target zone
        if (here != null && brakingNow) {
            if (distanceToTargetCornerM != null && distanceToTargetCornerM <= brakeZoneDistanceM) {
                if (currentLap[targetCornerIdx] == null) {
                    currentLap[targetCornerIdx] = GpsPoint(here.lat, here.lon)
                }
            }
        }

        // Detect lap boundary when target wraps from last corner to first
        val lastIdx = track.corners.size - 1
        val wrapped = (prevTargetCorner == lastIdx && targetCornerIdx == 0)
        if (wrapped) {
            val lapTime = nowMs - lapStartMs
            val improved = fastestLapTimeMs == null || lapTime < fastestLapTimeMs!!
            val complete = currentLap.all { it != null }
            if (improved && complete) {
                for (i in fastest.indices) fastest[i] = currentLap[i]
                fastestLapTimeMs = lapTime
                fastestLapNo = currentLapNo
            }
            // Reset for next lap
            currentLap.fill(null)
            currentLapNo += 1
            lapStartMs = nowMs
        }

        prevTargetCorner = targetCornerIdx
        prevAtCorner = atCornerNow
    }

    fun distanceToFastestBrakePointM(
        here: LatLon?, targetCornerIdx: Int
    ): Double? {
        val a = here ?: return null
        val b = fastest.getOrNull(targetCornerIdx) ?: return null
        return haversineMeters(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
    }
}



@Composable
fun RacingScreen(track: Track, repo: DriverRepo) {
    // Reuse your telemetry (it already provides target corner, braking, etc.)
    val telem = useTrainingTelemetry(repo) // fine for racing too

    // Race session manager (persist across recompositions)
    val session = remember(track, repo) { RaceSession(track, repo) }


    // Compute distance to FASTEST brake point for the target corner
    val distToBP = remember(telem.lastGps, telem.targetCornerIndex, session.fastest) {
        val tgt = telem.targetCornerIndex ?: 0
        session.distanceToFastestBrakePointM(telem.lastGps, tgt)
    }





    val approaching = remember(telem.targetCornerIndex, distToBP) { session.isApproaching() }

    // Use a safe warn distance: prefer brakeWarnDistanceM, else fall back to brakeZoneDistanceM
    val warnM = repo.track.brakeWarnDistanceM
        .takeIf { it > 0 } ?: repo.track.brakeZoneDistanceM

    val big = computeCountdown(
        distToBrakePointM = distToBP,
        brakeWarnDistanceM = warnM,    // add to your Track if not present; else use brakeZoneDistanceM
        cornerToleranceM = repo.track.cornerToleranceM
    )



// ---- BRAKE latch (hold 0 + labels for a bit) ----

    LaunchedEffect(telem.targetCornerIndex, distToBP) {
        val tgt = telem.targetCornerIndex ?: 0
        session.updateApproachHistory(tgt, distToBP) // you already had this call
        session.updateBrakeLatch(System.currentTimeMillis(), tgt, distToBP, repo.track.cornerToleranceM)
    }
    val tgtCorner = telem.targetCornerIndex ?: 0
    val brakeActive = session.isBrakeLatched(System.currentTimeMillis(), tgtCorner)

// Are we actively counting down (not in brake latch, and approaching)?
    val activeCountdown = (big != null && approaching && !brakeActive)

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
// --- Ring + Number + optional top/bottom !Brake! ---
        val cfg = LocalConfiguration.current
        val minDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).dp
        val ringSize = minDp * 0.80f
        val ringStroke = ringSize * 0.05f

        val ringTargetColor = when {
            brakeActive     -> Color.Red
            activeCountdown -> big!!.color
            else            -> Color(0xFFB0B0B0) // idle gray
        }
        val ringColor by animateColorAsState(ringTargetColor, animationSpec = tween(250))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.Center)
        ) {
            if (brakeActive) {
                Text("!Brake!", color = Color.Red, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }

            Box(
                modifier = Modifier
                    .size(ringSize)
                    .border(width = ringStroke, color = ringColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val dynamicSp = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.35f).sp
                when {
                    brakeActive     -> Text("0", color = Color.Red, fontSize = dynamicSp, fontWeight = FontWeight.ExtraBold)
                    activeCountdown -> Text(big!!.display, color = big!!.color, fontSize = dynamicSp, fontWeight = FontWeight.ExtraBold)
                    else            -> {} // idle: ring only
                }
            }

            if (brakeActive) {
                Text("!Brake!", color = Color.Red, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            }
        }



        // Footer HUD: lap info
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // --- TEMP DEBUG (remove after testing) ---
            val tgt = telem.targetCornerIndex ?: 0
            val hasBP = session.fastest.getOrNull(tgt) != null
            Text(
                "Dbg: tgt=C${tgt + 1}  hasBP=$hasBP  d=${distToBP?.roundToInt()}m  warn=${warnM.roundToInt()}m  approaching=${approaching}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )


            Text("Lap ${session.currentLapNo}", style = MaterialTheme.typography.titleMedium)
            val bestLap = session.fastestLapNo
            val bestTime = session.fastestLapTimeMs
            Text(
                text = if (bestLap != null && bestTime != null)
                    "Fastest: L$bestLap  •  ${formatLap(bestTime)}"
                else "Fastest: —",
                style = MaterialTheme.typography.bodyMedium
            )

            // Optional: debug line
            if (distToBP != null) {
                Text(
                    "→ ${distToBP.roundToInt()} m to brake point",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/* ----- helpers ----- */

private data class Countdown(val display: String, val color: Color)

private fun computeCountdown(
    distToBrakePointM: Double?,
    brakeWarnDistanceM: Double,
    cornerToleranceM: Double
): Countdown? {
    val d = distToBrakePointM ?: return null
    if (d > brakeWarnDistanceM) return null
    if (d <= cornerToleranceM) return null

    val span = (brakeWarnDistanceM - cornerToleranceM).coerceAtLeast(1.0)
    val pos = (d - cornerToleranceM).coerceIn(0.0, span)
    val step = ((pos / span) * 6.0).toInt().coerceIn(0, 5) + 1 // 1..6
    val color = if (step >= 4) Color(0xFF00C853) else Color(0xFFFFC400)
    return Countdown(step.toString(), color)
}


private fun formatLap(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val cs = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}
