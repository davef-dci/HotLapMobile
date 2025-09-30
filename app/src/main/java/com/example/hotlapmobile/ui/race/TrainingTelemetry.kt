package com.example.hotlapmobile.ui.race

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.config.LatLon
import com.example.hotlapmobile.data.CalibRepo
import com.example.hotlapmobile.data.DriverRepo
import com.example.hotlapmobile.data.PrefsRepo
import com.example.hotlapmobile.util.haversineMeters
import com.google.android.gms.location.*


// Tuning knobs for GPS corner determination - put into setup files later?
private const val MAX_GPS_AGE_MS = 500L     // ignore stale GPS
private const val MAX_GPS_ACC_M = 25f       // ignore low-accuracy GPS



data class TrainingTelemetry(
    val lastGps: LatLon? = null,
    val gpsAccuracyM: Float? = null,
    val gpsAgeMs: Long? = null,
    val speedMps: Float? = null,

    val forwardAccelMs2: Float = 0f,
    val forwardAccelG: Float = 0f,
    val brakingNow: Boolean = false,

    val nearestCornerIndex: Int? = null,
    val distanceToNearestCornerM: Double? = null,
    val expectedCornerIndex: Int? = null,
    val distanceToExpectedCornerM: Double? = null,

    val inBrakeZone: Boolean = false,

    val atCornerNow: Boolean = false,
    val targetCornerIndex: Int? = null,

    val trainedCornerCount: Int = 0,
    val totalCorners: Int = 0,
)
@SuppressLint("MissingPermission")
@Composable
fun useTrainingTelemetry(repo: DriverRepo, sessionKey: Int = 0): TrainingTelemetry  {
    val context = LocalContext.current

    val prefs = remember(context) { PrefsRepo(context) }
    val brakeThreshG = prefs.brakeThreshG.collectAsStateWithLifecycle(0.30f).value

    val calibRepo = remember(context) { CalibRepo(context) }
    val calib = calibRepo.state.collectAsStateWithLifecycle(initialValue = null).value
    val fwd: FloatArray? = calib?.vec

    // Telemetry state
    var telem by remember { mutableStateOf(TrainingTelemetry()) }

// --- Lua-style progression state ---
    var wasBraking by remember(sessionKey) { mutableStateOf(false) }
    var atCorner by remember(sessionKey) { mutableStateOf(false) }          // are we inside any corner tolerance this tick?
    var targetCornerIdx by remember(sessionKey) { mutableStateOf(0) }       // next corner we’re driving toward


    fun currentCounts(): Pair<Int, Int> {
        val n = repo.track.corners.size
        val trainedCount = repo.trainedCorners.count { it }
        return trainedCount to n
    }

    // --- GPS updates (Fused for now)
    DisposableEffect(Unit) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L)
            .setMinUpdateIntervalMillis(200L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                // --- GPS quality ---
                val accM = loc.accuracy
                val ageMs = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L)
                    .coerceAtLeast(0L)
                val fresh = (accM <= MAX_GPS_ACC_M) && (ageMs <= MAX_GPS_AGE_MS)

                val here = LatLon(loc.latitude, loc.longitude)

                // Always compute counts once up front
                val (trainedCount, totalCornersCount) = currentCounts()

                // If GPS is stale: update HUD + counts and bail
                if (!fresh) {
                    telem = telem.copy(
                        lastGps = here,
                        gpsAccuracyM = accM,
                        gpsAgeMs = ageMs,
                        speedMps = loc.speed,
                        trainedCornerCount = trainedCount,
                        totalCorners = totalCornersCount
                    )
                    return
                }

                // Single declaration of corner count
                val numCorners = repo.track.corners.size
                if (numCorners == 0) {
                    telem = telem.copy(
                        lastGps = here,
                        gpsAccuracyM = accM,
                        gpsAgeMs = ageMs,
                        speedMps = loc.speed,
                        trainedCornerCount = trainedCount,
                        totalCorners = totalCornersCount
                    )
                    return
                }

                val cornerTolM = repo.track.cornerToleranceM
                val brakeZoneM = repo.track.brakeZoneDistanceM

                // --- Lua-style: scan ALL corners each tick ---
                var nearAnyCorner = false
                var distanceToTarget = Double.NaN

                for (i in 0 until numCorners) {
                    val d = haversineMeters(here, repo.track.corners[i])

                    val insideTol = d < cornerTolM
                    if (insideTol) nearAnyCorner = true

                    // Advance target only on arrival edge
                    if (insideTol && !atCorner) {
                        targetCornerIdx = (i + 1) % numCorners
                    }

                    if (i == targetCornerIdx) {
                        distanceToTarget = d
                    }
                }

                // Corner state for this tick
                atCorner = nearAnyCorner

                val inTargetZone = distanceToTarget.isFinite() && distanceToTarget < brakeZoneM

                // Mark only on braking edge inside zone
                val enteredBraking = telem.brakingNow && !wasBraking
                if (enteredBraking && inTargetZone && !repo.trainedCorners[targetCornerIdx]) {
                    repo.markTrained(targetCornerIdx, here)
                    // Target advances when we physically enter next corner tolerance
                }
                wasBraking = telem.brakingNow

                // --- Single HUD update (includes counts) ---
                telem = telem.copy(
                    lastGps = here,
                    gpsAccuracyM = accM,
                    gpsAgeMs = ageMs,
                    speedMps = loc.speed,

                    nearestCornerIndex = targetCornerIdx,       // we show TARGET here
                    distanceToNearestCornerM = distanceToTarget,
                    expectedCornerIndex = null,
                    distanceToExpectedCornerM = null,
                    inBrakeZone = inTargetZone,

                    trainedCornerCount = trainedCount,
                    totalCorners = totalCornersCount
                )
            }

        }


        fused.requestLocationUpdates(req, callback, context.mainLooper)
        onDispose { fused.removeLocationUpdates(callback) }
    }

    // --- Accelerometer projected on calibrated forward vector (fallback to Y)
    DisposableEffect(fwd) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                val ax = e.values[0]
                val ay = e.values[1]
                val az = e.values[2]

                val forwardMs2 = if (fwd != null) ax * fwd[0] + ay * fwd[1] + az * fwd[2] else ay
                val forwardG = (forwardMs2 / 9.80665f)
                val braking = forwardG < -brakeThreshG

                // ✅ This is fine – updating a remembered state
                telem = telem.copy(
                    forwardAccelMs2 = forwardMs2,
                    forwardAccelG = forwardG,
                    brakingNow = braking
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }


        if (lin != null) sm.registerListener(listener, lin, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    return telem
}

