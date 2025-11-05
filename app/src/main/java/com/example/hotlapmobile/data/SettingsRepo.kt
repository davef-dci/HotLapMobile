package com.example.hotlapmobile.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.hotlapmobile.config.GlobalSettings
import com.example.hotlapmobile.config.GlobalSettingsDefaults

// We attach a DataStore<Preferences> to the Context.
// This pattern is the same idea as you're already using in PrefsRepo.
private val Context.settingsDataStore by preferencesDataStore(name = "global_settings")

class SettingsRepo(private val context: Context) {

    // --- Preference keys for each setting we care about ---
    private object Keys {
        val cornerTolM      = doublePreferencesKey("corner_tolerance_m")
        val brakeZoneM      = doublePreferencesKey("brake_zone_distance_m")
        val brakeWarnM      = doublePreferencesKey("brake_warn_distance_m")

        // G-G plot settings
        val ggMaxAbsG    = doublePreferencesKey("gg_max_abs_g")       // e.g., 1.5
        val ggTrailSec   = doublePreferencesKey("gg_trail_seconds")   // e.g., 3.0
    }

    /**
     * A Flow that always emits the *current* GlobalSettings.
     * If a value hasn't been saved yet, we fall back to the defaults
     * from GlobalSettingsDefaults.
     */
    val settings: Flow<GlobalSettings> = context.settingsDataStore.data.map { prefs ->
        val def = GlobalSettingsDefaults.default
        GlobalSettings(
            cornerToleranceM    = prefs[Keys.cornerTolM] ?: def.cornerToleranceM,
            brakeZoneDistanceM  = prefs[Keys.brakeZoneM] ?: def.brakeZoneDistanceM,
            brakeWarnDistanceM  = prefs[Keys.brakeWarnM] ?: def.brakeWarnDistanceM,
            // NEW: use persisted value if present, otherwise default
            ggMaxAbsG           = prefs[Keys.ggMaxAbsG] ?: def.ggMaxAbsG,
            ggTrailSeconds      = prefs[Keys.ggTrailSec] ?: def.ggTrailSeconds

        )
    }

    /**
     * Save *all three* values at once.
     * We'll call this from the Settings screen when the user taps "Save".
     */
    suspend fun updateAll(newVals: GlobalSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.cornerTolM] = newVals.cornerToleranceM
            prefs[Keys.brakeZoneM] = newVals.brakeZoneDistanceM
            prefs[Keys.brakeWarnM] = newVals.brakeWarnDistanceM

            prefs[Keys.ggMaxAbsG]  = newVals.ggMaxAbsG
            prefs[Keys.ggTrailSec] = newVals.ggTrailSeconds
        }
    }

    /**
     * Helper functions if you want to tweak just one field.
     * (These are optional but handy.)
     */
    suspend fun updateCornerTolerance(meters: Double) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.cornerTolM] = meters
        }
    }

    suspend fun updateBrakeZoneDistance(meters: Double) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.brakeZoneM] = meters
        }
    }

    suspend fun updateBrakeWarnDistance(meters: Double) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.brakeWarnM] = meters
        }
    }
}

