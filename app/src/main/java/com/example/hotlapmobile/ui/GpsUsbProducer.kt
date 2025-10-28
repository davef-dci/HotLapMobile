package com.example.hotlapmobile.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.hotlapmobile.util.UsbPuckGpsSource
import com.example.hotlapmobile.util.UsbGpsFix
import com.example.hotlapmobile.ui.GpsFix
import com.example.hotlapmobile.ui.LatestInputs

@Composable
fun GpsUsbProducer(
    latest: MutableState<LatestInputs>,
    source: com.example.hotlapmobile.util.UsbPuckGpsSource
) {


    // Bridge UsbGpsFix -> your GpsFix and publish to LatestInputs
    LaunchedEffect(Unit) {
        source.fixes.collect { fix: UsbGpsFix ->
            latest.value = latest.value.copy(
                gps = GpsFix(
                    lat = fix.lat,
                    lon = fix.lon,
                    tMillis = fix.tMillis
                )
            )
        }
    }
}
