package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spokenSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spoken_settings")

@Singleton
class SpokenSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Schalter Musik / Podcasts / Bücher in Start und Bibliothek (Standard: Musik). */
    val mode: Flow<SpokenMode> = context.spokenSettingsDataStore.data.map { preferences ->
        // „SPOKEN“ hieß der Modus, bevor er in Podcasts und Bücher geteilt wurde.
        preferences[KEY_MODE]?.let { name -> if (name == "SPOKEN") SpokenMode.BOOKS else SpokenMode.entries.firstOrNull { it.name == name } }
            ?: SpokenMode.MUSIC
    }

    suspend fun setMode(mode: SpokenMode) {
        context.spokenSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Suche über Musik, Podcasts und Bücher zugleich; aus (Standard) = nur der Inhalt des gewählten Modus. */
    val searchAll: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SEARCH_ALL] ?: false }

    suspend fun setSearchAll(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SEARCH_ALL] = enabled }
    }

    /** Bücher aus dem Ordner „Audiobooks“ auf diesem Gerät anzeigen (Standard: an, wie die lokale Musik). */
    val useLocalBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_BOOKS] ?: true }

    suspend fun setUseLocalBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_BOOKS] = enabled }
    }

    /** Podcast-Dateien aus dem Ordner „Podcasts“ auf diesem Gerät anzeigen (Standard: an). */
    val useLocalPodcasts: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_PODCASTS] ?: true }

    suspend fun setUseLocalPodcasts(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_PODCASTS] = enabled }
    }

    /** Zurückspulen beim Fortsetzen von Büchern. */
    val bookResume: Flow<ResumeRule> = context.spokenSettingsDataStore.data.map {
        ResumeRule(it[KEY_BOOK_REWIND_AFTER] ?: ResumeRule.DEFAULT_AFTER_S, it[KEY_BOOK_REWIND] ?: ResumeRule.DEFAULT_REWIND_S)
    }

    suspend fun setBookResume(rule: ResumeRule) {
        context.spokenSettingsDataStore.edit {
            it[KEY_BOOK_REWIND_AFTER] = rule.afterSeconds
            it[KEY_BOOK_REWIND] = rule.rewindSeconds
        }
    }

    /** Zurückspulen beim Fortsetzen von Podcast-Folgen. */
    val podcastResume: Flow<ResumeRule> = context.spokenSettingsDataStore.data.map {
        ResumeRule(it[KEY_PODCAST_REWIND_AFTER] ?: ResumeRule.DEFAULT_AFTER_S, it[KEY_PODCAST_REWIND] ?: ResumeRule.DEFAULT_REWIND_S)
    }

    suspend fun setPodcastResume(rule: ResumeRule) {
        context.spokenSettingsDataStore.edit {
            it[KEY_PODCAST_REWIND_AFTER] = rule.afterSeconds
            it[KEY_PODCAST_REWIND] = rule.rewindSeconds
        }
    }

    /** Ein Buch, das kurz nach einem Kapitelanfang beendet wurde, beginnt das Kapitel von vorn (Standard: aus). */
    val restartChapter: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_CHAPTER_RESTART] ?: false }

    suspend fun setRestartChapter(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_CHAPTER_RESTART] = enabled }
    }

    /** Bücher auch über Mobilfunk herunterladen (Standard: nur WLAN); unabhängig vom Schalter der Podcasts. */
    val bookMobileData: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_MOBILE] ?: false }

    suspend fun setBookMobileData(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_MOBILE] = enabled }
    }

    private companion object {
        val KEY_BOOK_MOBILE = booleanPreferencesKey("book_mobile_data")
        val KEY_BOOK_REWIND_AFTER = intPreferencesKey("book_rewind_after_s")
        val KEY_BOOK_REWIND = intPreferencesKey("book_rewind_s")
        val KEY_PODCAST_REWIND_AFTER = intPreferencesKey("podcast_rewind_after_s")
        val KEY_PODCAST_REWIND = intPreferencesKey("podcast_rewind_s")
        val KEY_CHAPTER_RESTART = booleanPreferencesKey("book_chapter_restart")
        val KEY_LOCAL_PODCASTS = booleanPreferencesKey("local_podcasts")
        val KEY_LOCAL_BOOKS = booleanPreferencesKey("local_books")
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_SEARCH_ALL = booleanPreferencesKey("search_all")
    }
}
