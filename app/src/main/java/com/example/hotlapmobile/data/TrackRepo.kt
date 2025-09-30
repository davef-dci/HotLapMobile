// data/TrackRepo.kt
package com.example.hotlapmobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.hotlapmobile.config.Track
import com.example.hotlapmobile.config.Tracks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.trackDataStore by preferencesDataStore(name = "track")

class TrackRepo(private val context: Context) {
    private object K { val Current = stringPreferencesKey("current_track") }

    val current: Flow<Track?> = context.trackDataStore.data.map { prefs ->
        val name = prefs[K.Current] ?: return@map null
        Tracks.all.find { it.name == name }
    }

    suspend fun save(track: Track) {
        context.trackDataStore.edit { it[K.Current] = track.name }
    }
}

