package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.sync.LocalSourcesDefault
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_LOCAL_PODCASTS = booleanPreferencesKey("local_podcasts")
private val KEY_LOCAL_BOOKS = booleanPreferencesKey("local_books")

private val Context.spokenSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "spoken_settings",
    produceMigrations = { context -> listOf(LocalSourcesDefault(context, listOf(KEY_LOCAL_BOOKS, KEY_LOCAL_PODCASTS))) },
)

@Singleton
class SpokenSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Schalter Musik / Podcasts / Bücher in Start und Bibliothek (Standard: Musik). Immer einer der eingeschalteten
     * [areas]; ist der gespeicherte ausgeschaltet, der erste davon.
     */
    val mode: Flow<SpokenMode> = context.spokenSettingsDataStore.data.map { preferences ->
        shownMode(preferences[KEY_MODE], enabledAreas(preferences[KEY_HIDDEN_AREAS]))
    }

    suspend fun setMode(mode: SpokenMode) {
        context.spokenSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Eingeschaltete Bereiche in der Reihenfolge des Schalters (Allgemein → Bereiche); nie leer, Standard: alle drei. */
    val areas: Flow<List<SpokenMode>> = context.spokenSettingsDataStore.data.map { enabledAreas(it[KEY_HIDDEN_AREAS]) }
        .onEach { lastAreas = it }

    /**
     * Zuletzt gelesene [areas]. Ein neuer Bildschirm zeichnet damit den Bereichsschalter gleich im ersten Bild richtig,
     * bevor der Flow liefert; sonst spränge der Kopf beim Wechsel zwischen Start und Bibliothek.
     */
    @Volatile
    var lastAreas: List<SpokenMode> = SpokenMode.entries
        private set

    /** Schaltet [area] ein oder aus; den letzten eingeschalteten Bereich lässt es an. */
    suspend fun setAreaEnabled(area: SpokenMode, enabled: Boolean) {
        context.spokenSettingsDataStore.edit { preferences ->
            val hidden = hiddenAfter(preferences[KEY_HIDDEN_AREAS].orEmpty(), area, enabled)
            if (hidden != null) preferences[KEY_HIDDEN_AREAS] = hidden
        }
    }

    /** Suche über Musik, Podcasts und Bücher zugleich; aus (Standard) = nur der Inhalt des gewählten Modus. */
    val searchAll: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SEARCH_ALL] ?: false }

    suspend fun setSearchAll(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SEARCH_ALL] = enabled }
    }

    /** Bücher aus dem Ordner „Audiobooks“ auf diesem Gerät anzeigen (Standard: aus, siehe [LocalSourcesDefault]). */
    val useLocalBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_BOOKS] ?: false }

    suspend fun setUseLocalBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_BOOKS] = enabled }
    }

    /** Podcast-Dateien aus dem Ordner „Podcasts“ auf diesem Gerät anzeigen (Standard: aus). */
    val useLocalPodcasts: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_PODCASTS] ?: false }

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

    /** Sprungweiten der Spulen-Knöpfe bei Büchern (Standard −10 s / +30 s). */
    val bookSeek: Flow<SeekSteps> = context.spokenSettingsDataStore.data.map {
        SeekSteps(it[KEY_BOOK_SEEK_BACK] ?: SeekSteps.DEFAULT_BACK_S, it[KEY_BOOK_SEEK_FORWARD] ?: SeekSteps.DEFAULT_FORWARD_S)
    }

    suspend fun setBookSeek(steps: SeekSteps) {
        context.spokenSettingsDataStore.edit {
            it[KEY_BOOK_SEEK_BACK] = steps.backSeconds
            it[KEY_BOOK_SEEK_FORWARD] = steps.forwardSeconds
        }
    }

    /** Sprungweiten der Spulen-Knöpfe bei Podcast-Folgen (Standard −10 s / +30 s). */
    val podcastSeek: Flow<SeekSteps> = context.spokenSettingsDataStore.data.map {
        SeekSteps(it[KEY_PODCAST_SEEK_BACK] ?: SeekSteps.DEFAULT_BACK_S, it[KEY_PODCAST_SEEK_FORWARD] ?: SeekSteps.DEFAULT_FORWARD_S)
    }

    suspend fun setPodcastSeek(steps: SeekSteps) {
        context.spokenSettingsDataStore.edit {
            it[KEY_PODCAST_SEEK_BACK] = steps.backSeconds
            it[KEY_PODCAST_SEEK_FORWARD] = steps.forwardSeconds
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
        val KEY_BOOK_SEEK_BACK = intPreferencesKey("book_seek_back_s")
        val KEY_BOOK_SEEK_FORWARD = intPreferencesKey("book_seek_forward_s")
        val KEY_PODCAST_SEEK_BACK = intPreferencesKey("podcast_seek_back_s")
        val KEY_PODCAST_SEEK_FORWARD = intPreferencesKey("podcast_seek_forward_s")
        val KEY_CHAPTER_RESTART = booleanPreferencesKey("book_chapter_restart")
        val KEY_MODE = stringPreferencesKey("mode")
        // Gespeichert sind die ausgeschalteten, damit ein neuer Bereich von selbst eingeschaltet ist.
        val KEY_HIDDEN_AREAS = stringSetPreferencesKey("hidden_areas")
        val KEY_SEARCH_ALL = booleanPreferencesKey("search_all")
    }
}

/** Die eingeschalteten Bereiche zu den gespeicherten ausgeschalteten [hidden]; sind alle aus (nur von außen möglich), alle. */
internal fun enabledAreas(hidden: Set<String>?): List<SpokenMode> =
    SpokenMode.entries.filter { it.name !in hidden.orEmpty() }.ifEmpty { SpokenMode.entries }

/** Der gezeigte Bereich zum gespeicherten Namen [saved]: er selbst, wenn eingeschaltet, sonst der erste von [areas]. */
internal fun shownMode(saved: String?, areas: List<SpokenMode>): SpokenMode {
    // „SPOKEN“ hieß der Modus, bevor er in Podcasts und Bücher geteilt wurde.
    val mode = saved?.let { name -> if (name == "SPOKEN") SpokenMode.BOOKS else SpokenMode.entries.firstOrNull { it.name == name } }
        ?: SpokenMode.MUSIC
    return mode.takeIf { it in areas } ?: areas.first()
}

/** Die ausgeschalteten Bereiche, nachdem [area] ein- bzw. ausgeschaltet ist; `null`, wenn sonst keiner mehr an wäre. */
internal fun hiddenAfter(hidden: Set<String>, area: SpokenMode, enabled: Boolean): Set<String>? {
    val next = if (enabled) hidden - area.name else hidden + area.name
    return next.takeIf { names -> SpokenMode.entries.any { it.name !in names } }
}
