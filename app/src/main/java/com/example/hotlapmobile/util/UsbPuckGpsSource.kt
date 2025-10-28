package com.example.hotlapmobile.util



import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.nio.charset.Charset
import kotlin.math.max


// Internal fix type for the USB reader; we’ll map this to your UI's GpsFix later.
data class UsbGpsFix(val lat: Double, val lon: Double, val tMillis: Long)
/**
 * Opens a Prolific PL2303 GPS puck and streams NMEA at ~10 Hz.
 * Emits your existing GpsFix (lat, lon, tMillis).
 *
 * Assumes GpsFix is defined in the same package (util).
 */


sealed interface UsbStatus {
    data object NotDetected : UsbStatus
    data object NoPermission : UsbStatus
    data object Connecting : UsbStatus
    data class Streaming(val hz: Double?) : UsbStatus
    data class Error(val message: String) : UsbStatus
}
class UsbPuckGpsSource(
    private val context: Context
) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.hotlapmobile.USB_PERMISSION"
        private const val VENDOR_PROLIFIC = 0x067B // 1659
        private const val BAUD = 115200
    }

    private val usb: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var readJob: Job? = null
    private var scope: CoroutineScope? = null

    private val _fixes = MutableSharedFlow<UsbGpsFix>(extraBufferCapacity = 16)
    val fixes: SharedFlow<UsbGpsFix> = _fixes

    fun start() {
        if (readJob?.isActive == true) return
        val s = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope = s
        readJob = s.launch { runReader() }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        scope?.cancel()
        scope = null
    }

    private suspend fun runReader() {
        val device = findProlific() ?: return
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            // simple polling loop; good enough for dev
            var granted = false
            repeat(50) {
                if (usb.hasPermission(device)) {
                    granted = true
                    return@repeat
                }
                kotlinx.coroutines.delay(100)
            }
            if (!granted) return
        }


        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
        val connection = usb.openDevice(device) ?: return
        val port = driver.ports.firstOrNull() ?: return

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Some PL2303s prefer these asserted
            port.dtr = true
            port.rts = true

            val readBuf = ByteArray(1024)
            val sb = StringBuilder()
            val ascii = Charset.forName("US-ASCII")

            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = try { port.read(readBuf, 200) } catch (_: Exception) { -1 }
                if (n != null && n > 0) {
                    val chunk = String(readBuf, 0, n, ascii)
                    for (c in chunk) {
                        if (c == '\n') {
                            val line = sb.toString().trim()
                            sb.setLength(0)
                            handleLine(line)
                        } else if (c != '\r') {
                            sb.append(c)
                        }
                    }
                } else {
                    // yield to avoid tight loop if nothing to read
                    yield()
                }
            }
        } catch (_: Exception) {
            // ignore for now; can add status reporting later
        } finally {
            try { port.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
    }

    private fun findProlific(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_PROLIFIC }

    private fun requestPermission(device: UsbDevice) {
        // Explicit intent for our own app (no implicit broadcast)
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            `package` = context.packageName
        }

        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE   // ✅ safe on Android 14+
        )

        usb.requestPermission(device, pi)
    }

    private fun handleLine(line: String) {
        // We only need RMC for lat/lon (you can add GGA later for HDOP/sats)
        // Accept any talker: $GPRMC / $GNRMC / $GARMC etc.
        if (!line.startsWith("$") || !line.contains("RMC,")) return
        if (!checksumOk(line)) return

        parseRmc(line)?.let { fix ->
            _fixes.tryEmit(fix)
        }
    }

    private fun checksumOk(s: String): Boolean {
        val star = s.lastIndexOf('*')
        if (star <= 0 || star + 3 > s.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor s[i].code
        val want = s.substring(star + 1).uppercase()
        val have = "%02X".format(cs)
        return want == have
    }

    private fun parseRmc(s: String): UsbGpsFix? {
        // $--RMC,hhmmss.sss,A,llll.ll,a,yyyyy.yy,a,...*CS
        val star = s.indexOf('*').let { if (it < 0) s.length else it }
        val f = s.substring(1, star).split(',')
        // fields: 0:--RMC, 1:time, 2:status, 3:lat,4:N/S, 5:lon,6:E/W, ...
        if (f.size < 7) return null
        if (f[2] != "A") return null // only when valid

        val lat = dmToDeg(f[3], f[4]) ?: return null
        val lon = dmToDeg(f[5], f[6]) ?: return null

        val tMillis = android.os.SystemClock.elapsedRealtime()
        return UsbGpsFix(lat = lat, lon = lon, tMillis = tMillis)
    }

    /**
     * Convert NMEA degrees+minutes string to decimal degrees.
     *   lat: ddmm.mmmm  (e.g. 4307.1234 -> 43 + 07.1234/60 = 43.1187)
     *   lon: dddmm.mmmm (e.g. 08932.5678 -> 89 + 32.5678/60 = 89.5431)
     */
    private fun dmToDeg(dm: String?, hemi: String?): Double? {
        if (dm.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = dm.indexOf('.')
        if (dot < 0 || dot < 2) return null
        val degDigits = if (dot > 4) 3 else 2     // 3 for lon, 2 for lat
        val deg = dm.substring(0, degDigits).toIntOrNull() ?: return null
        val min = dm.substring(degDigits).toDoubleOrNull() ?: return null
        var v = deg + (min / 60.0)
        if (hemi.equals("S", true) || hemi.equals("W", true)) v = -v
        return v
    }

}

