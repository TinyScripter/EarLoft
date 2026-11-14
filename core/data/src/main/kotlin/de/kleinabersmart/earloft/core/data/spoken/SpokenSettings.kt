package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.sync.LocalSourcesDefault
import de.kleinabersmart.earloft.core.model.AuthorSort
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookSort
import de.kleinabersmart.earloft.core.model.SeriesSort
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

internal val KEY_LOCAL_PODCASTS = booleanPreferencesKey("local_podcasts")
internal val KEY_LOCAL_BOOKS = booleanPreferencesKey("local_books")
private val KEY_ARD_SOUNDS_BOOKS = booleanPreferencesKey("ard_sounds_books")
private val KEY_ARD_SHOWS = stringSetPreferencesKey("ard_shows")
private val KEY_ARD_SEEN = stringSetPreferencesKey("ard_seen")

private const val ARD_SEPARATOR = '\t'

internal val Context.spokenSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
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

    /**
     * Hörbücher und Hörspiele aus ARD Sounds (`books_improvements.md` M9, Standard: aus): Seite „ARD Sounds“ in *Bücher* und Abgleich
     * der Bücher von dort. Aus fragt die App ARD Sounds nicht mehr; Bücher in der Bibliothek bleiben hörbar.
     */
    val ardSoundsBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_ARD_SOUNDS_BOOKS] ?: false }

    suspend fun setArdSoundsBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_ARD_SOUNDS_BOOKS] = enabled }
    }

    /**
     * Abonnierte Sendungen und Reihen aus ARD Sounds, nach Titel sortiert. Sie stehen mit den Einstellungen in der Sicherung; je Eintrag
     * `Kennung<Tab>Titel`.
     */
    val ardSubscriptions: Flow<List<ArdSubscription>> = context.spokenSettingsDataStore.data.map { preferences ->
        preferences[KEY_ARD_SHOWS].orEmpty().mapNotNull { entry ->
            entry.split(ARD_SEPARATOR, limit = 2).takeIf { it.size == 2 }?.let { (id, title) -> ArdSubscription(id, title) }
        }.sortedBy { it.title.lowercase() }
    }

    /** Die Bücher von [showId], die der Abgleich schon kennt: Was fehlt, ist neu (Eintrag `Sendung<Tab>Buch`). */
    suspend fun ardSeen(showId: String): Set<String> {
        val prefix = showId + ARD_SEPARATOR
        return context.spokenSettingsDataStore.data.first()[KEY_ARD_SEEN].orEmpty().filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
    }

    suspend fun addArdSeen(showId: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.spokenSettingsDataStore.edit { it[KEY_ARD_SEEN] = it[KEY_ARD_SEEN].orEmpty() + keys.map { key -> showId + ARD_SEPARATOR + key } }
    }

    /** Abonniert [showId]; [seen] sind die Bücher, die schon da sind und deshalb nicht als neu gelten. */
    suspend fun subscribeArd(showId: String, title: String, seen: Collection<String>) {
        context.spokenSettingsDataStore.edit { preferences ->
            val prefix = showId + ARD_SEPARATOR
            preferences[KEY_ARD_SHOWS] = preferences[KEY_ARD_SHOWS].orEmpty().filterNot { it.startsWith(prefix) }.toSet() +
                (prefix + title.replace(ARD_SEPARATOR, ' '))
            preferences[KEY_ARD_SEEN] = preferences[KEY_ARD_SEEN].orEmpty() + seen.map { prefix + it }
        }
    }

    suspend fun unsubscribeArd(showId: String) {
        context.spokenSettingsDataStore.edit { preferences ->
            val prefix = showId + ARD_SEPARATOR
            preferences[KEY_ARD_SHOWS] = preferences[KEY_ARD_SHOWS].orEmpty().filterNot { it.startsWith(prefix) }.toSet()
            preferences[KEY_ARD_SEEN] = preferences[KEY_ARD_SEEN].orEmpty().filterNot { it.startsWith(prefix) }.toSet()
        }
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

    /**
     * Vorgabe für Tempo, Stille kürzen und Lautstärke anheben bei Büchern (Konzept 12.4, Standard 1,0 ohne Effekte); ein
     * Buch mit eigenen Werten behält sie.
     */
    val bookEffects: Flow<SpokenEffects> = context.spokenSettingsDataStore.data.map {
        SpokenEffects(it[KEY_BOOK_SPEED] ?: 1f, it[KEY_BOOK_SKIP_SILENCE] ?: false, it[KEY_BOOK_BOOST] ?: 0)
    }

    suspend fun setBookEffects(effects: SpokenEffects) {
        context.spokenSettingsDataStore.edit {
            it[KEY_BOOK_SPEED] = effects.speed
            it[KEY_BOOK_SKIP_SILENCE] = effects.skipSilence
            it[KEY_BOOK_BOOST] = effects.volumeBoostDb
        }
    }

    /** Wie [bookEffects] für Podcast-Folgen; ein Abo kann eigene Werte haben, eine Folge ebenso. */
    val podcastEffects: Flow<SpokenEffects> = context.spokenSettingsDataStore.data.map {
        SpokenEffects(it[KEY_PODCAST_SPEED] ?: 1f, it[KEY_PODCAST_SKIP_SILENCE] ?: false, it[KEY_PODCAST_BOOST] ?: 0)
    }

    suspend fun setPodcastEffects(effects: SpokenEffects) {
        context.spokenSettingsDataStore.edit {
            it[KEY_PODCAST_SPEED] = effects.speed
            it[KEY_PODCAST_SKIP_SILENCE] = effects.skipSilence
            it[KEY_PODCAST_BOOST] = effects.volumeBoostDb
        }
    }

    /** Knöpfe in Benachrichtigung und Android Auto bei Büchern ([BookButtons], Standard: Spulen). */
    val bookButtons: Flow<BookButtons> = context.spokenSettingsDataStore.data.map { prefs ->
        BookButtons.entries.firstOrNull { it.name == prefs[KEY_BOOK_BUTTONS] } ?: BookButtons.SEEK
    }

    suspend fun setBookButtons(buttons: BookButtons) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_BUTTONS] = buttons.name }
    }

    /** Ein Buch, das kurz nach einem Kapitelanfang beendet wurde, beginnt das Kapitel von vorn (Standard: aus). */
    val restartChapter: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_CHAPTER_RESTART] ?: false }

    suspend fun setRestartChapter(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_CHAPTER_RESTART] = enabled }
    }

    /**
     * Schütteln verlängert den Sleep-Timer bei Büchern und Folgen (Standard: an); ein Schalter für beide Bereiche, zu
     * finden unter Bücher und unter Podcasts → Wiedergabe.
     */
    val shakeToExtend: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SLEEP_SHAKE] ?: true }

    suspend fun setShakeToExtend(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SLEEP_SHAKE] = enabled }
    }

    /** Bücher auch über Mobilfunk herunterladen (Standard: nur WLAN); unabhängig vom Schalter der Podcasts. */
    val bookMobileData: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_MOBILE] ?: false }

    suspend fun setBookMobileData(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_MOBILE] = enabled }
    }

    /**
     * Vor dem Streamen eines Buchs oder einer Folge über mobile Daten fragen (Standard: nein, wie bisher); gilt für Starts
     * aus der App, nicht von der Uhr.
     */
    val askBeforeMobileStreaming: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_ASK_MOBILE_STREAM] ?: false }

    suspend fun setAskBeforeMobileStreaming(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_ASK_MOBILE_STREAM] = enabled }
    }

    /** Die ersten Bücher aus „Weiterhören“ automatisch laden, nur im WLAN ([automaticBookDownloads]; Standard: aus). */
    val bookAutoStarted: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_AUTO_STARTED] ?: false }

    suspend fun setBookAutoStarted(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_AUTO_STARTED] = enabled }
    }

    /** Den nächsten Band einer Serie automatisch laden, nur im WLAN ([automaticBookDownloads]; Standard: aus). */
    val bookAutoNextInSeries: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_AUTO_NEXT] ?: false }

    suspend fun setBookAutoNextInSeries(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_AUTO_NEXT] = enabled }
    }

    /**
     * Bücher aus ARD Sounds in der Bibliothek, die bald ablaufen, im WLAN laden ([automaticBookDownloads], [ARD_EXPIRY_LOAD_DAYS] Tage
     * vorher; Standard: an – sonst verschwände ein Buch, das man behalten wollte, mit dem Ablauf bei ARD Sounds).
     */
    val bookLoadExpiring: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_LOAD_EXPIRING] ?: true }

    suspend fun setBookLoadExpiring(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_LOAD_EXPIRING] = enabled }
    }

    /**
     * Nach wie vielen Tagen der Download eines beendeten Buchs gelöscht wird: wie bei Folgen 0 = sofort, eine Anzahl Tage
     * oder [PodcastSettings.DELETE_HEARD_NEVER] (Standard); die Auswahl wie dort ([PodcastSettings.DELETE_HEARD_CHOICES]).
     */
    val bookDeleteFinishedDays: Flow<Int> =
        context.spokenSettingsDataStore.data.map { it[KEY_BOOK_DELETE_FINISHED] ?: PodcastSettings.DELETE_HEARD_NEVER }

    suspend fun setBookDeleteFinishedDays(days: Int) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_DELETE_FINISHED] = days.coerceAtLeast(PodcastSettings.DELETE_HEARD_NEVER) }
    }

    /** Reihenfolge und Filter des Reiters „Alle“ der Bücher (Standard: nach Titel, alle). */
    val bookSort: Flow<BookSort> = context.spokenSettingsDataStore.data.map { prefs ->
        BookSort.entries.firstOrNull { it.name == prefs[KEY_BOOK_SORT] } ?: BookSort.TITLE
    }

    suspend fun setBookSort(sort: BookSort) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_SORT] = sort.name }
    }

    val bookFilter: Flow<BookFilter> = context.spokenSettingsDataStore.data.map { prefs ->
        BookFilter.entries.firstOrNull { it.name == prefs[KEY_BOOK_FILTER] } ?: BookFilter.ALL
    }

    suspend fun setBookFilter(filter: BookFilter) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_FILTER] = filter.name }
    }

    /** Genre-Filter des Reiters „Alle“ (`books_improvements.md` S9); `null` = alle Genres. */
    val bookGenre: Flow<String?> = context.spokenSettingsDataStore.data.map { prefs -> prefs[KEY_BOOK_GENRE]?.ifEmpty { null } }

    suspend fun setBookGenre(genre: String?) {
        context.spokenSettingsDataStore.edit { if (genre == null) it.remove(KEY_BOOK_GENRE) else it[KEY_BOOK_GENRE] = genre }
    }

    /** Bibliotheks-Filter des Reiters „Alle“ (Kennung der Audiobookshelf-Bibliothek, M3); `null` = alle. */
    val bookLibrary: Flow<String?> = context.spokenSettingsDataStore.data.map { prefs -> prefs[KEY_BOOK_LIBRARY]?.ifEmpty { null } }

    suspend fun setBookLibrary(libraryId: String?) {
        context.spokenSettingsDataStore.edit { if (libraryId == null) it.remove(KEY_BOOK_LIBRARY) else it[KEY_BOOK_LIBRARY] = libraryId }
    }

    /** Reihenfolge der Reiter „Serien“ und „Autoren“ (Standard: nach Name). */
    val seriesSort: Flow<SeriesSort> = context.spokenSettingsDataStore.data.map { prefs ->
        SeriesSort.entries.firstOrNull { it.name == prefs[KEY_SERIES_SORT] } ?: SeriesSort.NAME
    }

    suspend fun setSeriesSort(sort: SeriesSort) {
        context.spokenSettingsDataStore.edit { it[KEY_SERIES_SORT] = sort.name }
    }

    val authorSort: Flow<AuthorSort> = context.spokenSettingsDataStore.data.map { prefs ->
        AuthorSort.entries.firstOrNull { it.name == prefs[KEY_AUTHOR_SORT] } ?: AuthorSort.NAME
    }

    suspend fun setAuthorSort(sort: AuthorSort) {
        context.spokenSettingsDataStore.edit { it[KEY_AUTHOR_SORT] = sort.name }
    }

    private companion object {
        val KEY_BOOK_SORT = stringPreferencesKey("book_sort")
        val KEY_SERIES_SORT = stringPreferencesKey("series_sort")
        val KEY_BOOK_GENRE = stringPreferencesKey("book_genre")
        val KEY_BOOK_LIBRARY = stringPreferencesKey("book_library")
        val KEY_AUTHOR_SORT = stringPreferencesKey("author_sort")
        val KEY_BOOK_FILTER = stringPreferencesKey("book_filter")
        val KEY_BOOK_MOBILE = booleanPreferencesKey("book_mobile_data")
        val KEY_ASK_MOBILE_STREAM = booleanPreferencesKey("ask_before_mobile_streaming")
        val KEY_BOOK_AUTO_STARTED = booleanPreferencesKey("book_auto_started")
        val KEY_BOOK_AUTO_NEXT = booleanPreferencesKey("book_auto_next_in_series")
        val KEY_BOOK_LOAD_EXPIRING = booleanPreferencesKey("book_load_expiring")
        val KEY_BOOK_DELETE_FINISHED = intPreferencesKey("book_delete_finished_days")
        val KEY_BOOK_REWIND_AFTER = intPreferencesKey("book_rewind_after_s")
        val KEY_BOOK_REWIND = intPreferencesKey("book_rewind_s")
        val KEY_PODCAST_REWIND_AFTER = intPreferencesKey("podcast_rewind_after_s")
        val KEY_PODCAST_REWIND = intPreferencesKey("podcast_rewind_s")
        val KEY_BOOK_SEEK_BACK = intPreferencesKey("book_seek_back_s")
        val KEY_BOOK_SEEK_FORWARD = intPreferencesKey("book_seek_forward_s")
        val KEY_PODCAST_SEEK_BACK = intPreferencesKey("podcast_seek_back_s")
        val KEY_PODCAST_SEEK_FORWARD = intPreferencesKey("podcast_seek_forward_s")
        val KEY_CHAPTER_RESTART = booleanPreferencesKey("book_chapter_restart")
        val KEY_BOOK_BUTTONS = stringPreferencesKey("book_buttons")
        val KEY_SLEEP_SHAKE = booleanPreferencesKey("sleep_shake")
        val KEY_BOOK_SPEED = floatPreferencesKey("book_speed")
        val KEY_BOOK_SKIP_SILENCE = booleanPreferencesKey("book_skip_silence")
        val KEY_BOOK_BOOST = intPreferencesKey("book_volume_boost_db")
        val KEY_PODCAST_SPEED = floatPreferencesKey("podcast_speed")
        val KEY_PODCAST_SKIP_SILENCE = booleanPreferencesKey("podcast_skip_silence")
        val KEY_PODCAST_BOOST = intPreferencesKey("podcast_volume_boost_db")
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
