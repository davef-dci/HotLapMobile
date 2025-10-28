package com.example.hotlapmobile.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import com.example.hotlapmobile.util.UsbGpsFix
import com.example.hotlapmobile.util.UsbPuckGpsSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.statusBarsPadding

@Composable
fun UsbPuckDebugPanel(
    modifier: Modifier = Modifier,
    source: com.example.hotlapmobile.util.UsbPuckGpsSource
) {
    var last by remember { mutableStateOf<com.example.hotlapmobile.util.UsbGpsFix?>(null) }
    var lastTs by remember { mutableStateOf<Long?>(null) }
    var countInBucket by remember { mutableStateOf(0) }
    var hz by remember { mutableStateOf(0f) }
    // Collect fixes and update stats
    LaunchedEffect(Unit) {
        var bucketStart = SystemClock.elapsedRealtime()
        val windowMs = 1000L

        source.fixes.collect { fix ->
            last = fix
            lastTs = fix.tMillis
            countInBucket++

            val now = SystemClock.elapsedRealtime()
            if (now - bucketStart >= windowMs) {
                hz = countInBucket.toFloat() * 1000f / (now - bucketStart).toFloat()
                countInBucket = 0
                bucketStart = now     // ✅ reset window start
            }
        }
    }

    // Compute age every recomposition
    val ageMs = lastTs?.let { SystemClock.elapsedRealtime() - it } ?: -1L

    // 🔽 NEW: build stable, already-formatted strings
    // This prevents flicker between "pretty" and "raw Double"
    val hzStr = remember(hz) {
        if (hz > 0f) "%.1f".format(hz) else "—"
    }

    val ageStr = remember(ageMs) {
        if (ageMs >= 0) ageMs.toString() else "—"
    }

    val latStr = remember(last?.lat) {
        last?.lat?.let { "%.6f".format(it) } ?: "—"
    }

    val lonStr = remember(last?.lon) {
        last?.lon?.let { "%.6f".format(it) } ?: "—"
    }

    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("USB GPS (PL2303) Debug")
        Text("Hz (est): $hzStr")
        Text("Age ms: $ageStr")
        Text("Lat: $latStr")
        Text("Lon: $lonStr")
    }
}
