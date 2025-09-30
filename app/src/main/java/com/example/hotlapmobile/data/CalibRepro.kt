package com.example.hotlapmobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibDataStore by preferencesDataStore(name = "calibration")

data class CalibState(
    val vec: FloatArray?,     // null = not calibrated
    val savedAtEpochMs: Long? // when it was saved
)

class CalibRepo(private val context: Context) {
    private object K {
        val X = floatPreferencesKey("calib_x")
        val Y = floatPreferencesKey("calib_y")
        val Z = floatPreferencesKey("calib_z")
        val T = longPreferencesKey("calib_saved_at")
    }

    val state: Flow<CalibState> = context.calibDataStore.data.map { p ->
        val has = p[K.X] != null && p[K.Y] != null && p[K.Z] != null
        CalibState(
            vec = if (has) floatArrayOf(p[K.X]!!, p[K.Y]!!, p[K.Z]!!) else null,
            savedAtEpochMs = p[K.T]
        )
    }

    suspend fun save(vec: FloatArray, nowMs: Long = System.currentTimeMillis()) {
        require(vec.size == 3)
        context.calibDataStore.edit { p ->
            p[K.X] = vec[0]; p[K.Y] = vec[1]; p[K.Z] = vec[2]
            p[K.T] = nowMs
        }
    }

    suspend fun clear() {
        context.calibDataStore.edit { it.clear() }
    }
}

