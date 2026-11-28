package com.github.danieladams456.morsebook.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.danieladams456.morsebook.DEFAULT_TONE_HZ
import com.github.danieladams456.morsebook.DEFAULT_WPM
import com.github.danieladams456.morsebook.SynthesisOptions
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore("playback")
private val wpm = intPreferencesKey("wpm")
private val toneHz = intPreferencesKey("tone_hz")

internal class PlaybackSettings(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    suspend fun load(): SynthesisOptions = store.data.first().let {
        SynthesisOptions(it[wpm] ?: DEFAULT_WPM, it[toneHz] ?: DEFAULT_TONE_HZ)
    }

    suspend fun save(settings: SynthesisOptions) {
        store.edit {
            it[wpm] = settings.wpm
            it[toneHz] = settings.toneHz
        }
    }
}
