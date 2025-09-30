package com.example.hotlapmobile.data

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefsDataStore by preferencesDataStore(name = "prefs")

class PrefsRepo(private val context: Context) {
    private object Keys {
        val BrakeThresh = floatPreferencesKey("brake_thresh_g")
    }

    /** Threshold in +g, e.g. 0.30 means trigger when forward accel < -0.30g */
    val brakeThreshG: Flow<Float> = context.prefsDataStore.data.map { p ->
        p[Keys.BrakeThresh] ?: 0.20f
    }

    suspend fun saveBrakeThresh(valueG: Float) {
        context.prefsDataStore.edit { it[Keys.BrakeThresh] = valueG.coerceIn(0.10f, 1.00f) }
    }
}
