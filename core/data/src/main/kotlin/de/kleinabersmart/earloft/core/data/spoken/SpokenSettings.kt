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
import de.kleinabersmart.earloft.core.data.backup.SettingsMerge
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
private val KEY_ARD_GONE = stringSetPreferencesKey("ard_gone")
private val KEY_ARD_MISSING = stringSetPreferencesKey("ard_missing")

private const val ARD_SEPARATOR = '\t'

/**
 * User data in the settings, merged when restoring a backup: subscriptions (a show once, the local one takes precedence) and
 * the known books per show (otherwise removed ones would come back as "new"); the markers for expired books belong to this device.
 */
internal val SPOKEN_SETTINGS_MERGES: Map<String, SettingsMerge> = mapOf(
    KEY_ARD_SHOWS.name to SettingsMerge { current, saved, _ ->
        @Suppress("UNCHECKED_CAST")
        val here = (current as? Set<String>).orEmpty()
        val ids = here.mapTo(HashSet()) { it.substringBefore(ARD_SEPARATOR) }
        @Suppress("UNCHECKED_CAST")
        here + (saved as Set<String>).filter { it.substringBefore(ARD_SEPARATOR) !in ids }
    },
    KEY_ARD_SEEN.name to SettingsMerge.UNION,
    KEY_ARD_GONE.name to SettingsMerge.KEEP,
    KEY_ARD_MISSING.name to SettingsMerge.KEEP,
)

internal val Context.spokenSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "spoken_settings",
    produceMigrations = { context -> listOf(LocalSourcesDefault(context, listOf(KEY_LOCAL_BOOKS, KEY_LOCAL_PODCASTS))) },
)

@Singleton
class SpokenSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Music / Podcasts / Books switch on Home and Library (default: music). Always one of the enabled
     * [areas]; if the stored one is disabled, the first of them.
     */
    val mode: Flow<SpokenMode> = context.spokenSettingsDataStore.data.map { preferences ->
        shownMode(preferences[KEY_MODE], enabledAreas(preferences[KEY_HIDDEN_AREAS]))
    }

    suspend fun setMode(mode: SpokenMode) {
        context.spokenSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Enabled sections in the order of the switch (General → Sections); never empty, default: all three. */
    val areas: Flow<List<SpokenMode>> = context.spokenSettingsDataStore.data.map { enabledAreas(it[KEY_HIDDEN_AREAS]) }
        .onEach { lastAreas = it }

    /**
     * Most recently read [areas]. A new screen uses it to draw the section switch correctly in the very first frame,
     * before the flow emits; otherwise the header would jump when switching between Home and Library.
     */
    @Volatile
    var lastAreas: List<SpokenMode> = SpokenMode.entries
        private set

    /** Enables or disables [area]; it leaves the last enabled section on. */
    suspend fun setAreaEnabled(area: SpokenMode, enabled: Boolean) {
        context.spokenSettingsDataStore.edit { preferences ->
            val hidden = hiddenAfter(preferences[KEY_HIDDEN_AREAS].orEmpty(), area, enabled)
            if (hidden != null) preferences[KEY_HIDDEN_AREAS] = hidden
        }
    }

    /** Search across music, podcasts and books at once; off (default) = only the content of the selected mode. */
    val searchAll: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SEARCH_ALL] ?: false }

    suspend fun setSearchAll(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SEARCH_ALL] = enabled }
    }

    /** Show books from the "Audiobooks" folder on this device (default: off, see [LocalSourcesDefault]). */
    val useLocalBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_BOOKS] ?: false }

    suspend fun setUseLocalBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_BOOKS] = enabled }
    }

    /**
     * Audiobooks and radio plays from ARD Sounds (`books_improvements.md` M9, default: off): "ARD Sounds" page in *Books* and sync
     * of the books from there. When off, the app no longer queries ARD Sounds; books in the library stay playable.
     */
    val ardSoundsBooks: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_ARD_SOUNDS_BOOKS] ?: false }

    suspend fun setArdSoundsBooks(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_ARD_SOUNDS_BOOKS] = enabled }
    }

    /**
     * Subscribed shows and series from ARD Sounds, sorted by title. They are in the backup along with the settings; each entry
     * `id<Tab>title`.
     */
    val ardSubscriptions: Flow<List<ArdSubscription>> = context.spokenSettingsDataStore.data.map { preferences ->
        preferences[KEY_ARD_SHOWS].orEmpty().mapNotNull { entry ->
            entry.split(ARD_SEPARATOR, limit = 2).takeIf { it.size == 2 }?.let { (id, title) -> ArdSubscription(id, title) }
        }.sortedBy { it.title.lowercase() }
    }

    /** The books of [showId] that the sync already knows: whatever is missing is new (entry `show<Tab>book`). */
    suspend fun ardSeen(showId: String): Set<String> {
        val prefix = showId + ARD_SEPARATOR
        return context.spokenSettingsDataStore.data.first()[KEY_ARD_SEEN].orEmpty().filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
    }

    suspend fun addArdSeen(showId: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.spokenSettingsDataStore.edit { it[KEY_ARD_SEEN] = it[KEY_ARD_SEEN].orEmpty() + keys.map { key -> showId + ARD_SEPARATOR + key } }
    }

    /** When ARD Sounds was last asked about an expired book: book id → timestamp (entry `book<Tab>timestamp`). */
    suspend fun ardGoneChecks(): Map<String, Long> = ardTimes(KEY_ARD_GONE)

    /** Remembers [at] as the last query for book [key]; `null` forgets it. */
    suspend fun setArdGoneCheck(key: String, at: Long?) = setArdTime(KEY_ARD_GONE, key, at)

    /**
     * Since when ARD Sounds no longer delivers a book that doesn't count as gone yet: book id → time of the first empty
     * response. Only a second, later one confirms it (review F6).
     */
    suspend fun ardMissing(): Map<String, Long> = ardTimes(KEY_ARD_MISSING)

    /** Remembers [at] as the first empty response for book [key]; `null` forgets it. */
    suspend fun setArdMissing(key: String, at: Long?) = setArdTime(KEY_ARD_MISSING, key, at)

    private suspend fun ardTimes(preference: Preferences.Key<Set<String>>): Map<String, Long> =
        context.spokenSettingsDataStore.data.first()[preference].orEmpty().mapNotNull { entry ->
            entry.split(ARD_SEPARATOR, limit = 2).takeIf { it.size == 2 }?.let { (key, at) -> at.toLongOrNull()?.let { key to it } }
        }.toMap()

    private suspend fun setArdTime(preference: Preferences.Key<Set<String>>, key: String, at: Long?) {
        context.spokenSettingsDataStore.edit { preferences ->
            val prefix = key + ARD_SEPARATOR
            preferences[preference] = preferences[preference].orEmpty().filterNot { it.startsWith(prefix) }.toSet() +
                listOfNotNull(at?.let { prefix + it })
        }
    }

    /** Subscribes to [showId]; [seen] are the books that are already there and therefore don't count as new. */
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

    /** Show podcast files from the "Podcasts" folder on this device (default: off). */
    val useLocalPodcasts: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_LOCAL_PODCASTS] ?: false }

    suspend fun setUseLocalPodcasts(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_LOCAL_PODCASTS] = enabled }
    }

    /** Rewind when resuming books. */
    val bookResume: Flow<ResumeRule> = context.spokenSettingsDataStore.data.map {
        ResumeRule(it[KEY_BOOK_REWIND_AFTER] ?: ResumeRule.DEFAULT_AFTER_S, it[KEY_BOOK_REWIND] ?: ResumeRule.DEFAULT_REWIND_S)
    }

    suspend fun setBookResume(rule: ResumeRule) {
        context.spokenSettingsDataStore.edit {
            it[KEY_BOOK_REWIND_AFTER] = rule.afterSeconds
            it[KEY_BOOK_REWIND] = rule.rewindSeconds
        }
    }

    /** Rewind when resuming podcast episodes. */
    val podcastResume: Flow<ResumeRule> = context.spokenSettingsDataStore.data.map {
        ResumeRule(it[KEY_PODCAST_REWIND_AFTER] ?: ResumeRule.DEFAULT_AFTER_S, it[KEY_PODCAST_REWIND] ?: ResumeRule.DEFAULT_REWIND_S)
    }

    suspend fun setPodcastResume(rule: ResumeRule) {
        context.spokenSettingsDataStore.edit {
            it[KEY_PODCAST_REWIND_AFTER] = rule.afterSeconds
            it[KEY_PODCAST_REWIND] = rule.rewindSeconds
        }
    }

    /** Skip intervals of the seek buttons for books (default −10 s / +30 s). */
    val bookSeek: Flow<SeekSteps> = context.spokenSettingsDataStore.data.map {
        SeekSteps(it[KEY_BOOK_SEEK_BACK] ?: SeekSteps.DEFAULT_BACK_S, it[KEY_BOOK_SEEK_FORWARD] ?: SeekSteps.DEFAULT_FORWARD_S)
    }

    suspend fun setBookSeek(steps: SeekSteps) {
        context.spokenSettingsDataStore.edit {
            it[KEY_BOOK_SEEK_BACK] = steps.backSeconds
            it[KEY_BOOK_SEEK_FORWARD] = steps.forwardSeconds
        }
    }

    /** Skip intervals of the seek buttons for podcast episodes (default −10 s / +30 s). */
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
     * Default for speed, trim silence and volume boost for books (concept 12.4, default 1.0 without effects); a
     * book with its own values keeps them.
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

    /** Like [bookEffects] for podcast episodes; a subscription can have its own values, and so can an episode. */
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

    /** Buttons in the notification and Android Auto for books ([BookButtons], default: seek). */
    val bookButtons: Flow<BookButtons> = context.spokenSettingsDataStore.data.map { prefs ->
        BookButtons.entries.firstOrNull { it.name == prefs[KEY_BOOK_BUTTONS] } ?: BookButtons.SEEK
    }

    suspend fun setBookButtons(buttons: BookButtons) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_BUTTONS] = buttons.name }
    }

    /** A book that was stopped shortly after a chapter start begins the chapter from the beginning (default: off). */
    val restartChapter: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_CHAPTER_RESTART] ?: false }

    suspend fun setRestartChapter(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_CHAPTER_RESTART] = enabled }
    }

    /**
     * Shaking extends the sleep timer for books and episodes (default: on); one switch for both sections, found
     * under Books and under Podcasts → Playback.
     */
    val shakeToExtend: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_SLEEP_SHAKE] ?: true }

    suspend fun setShakeToExtend(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_SLEEP_SHAKE] = enabled }
    }

    /** Download books over mobile data too (default: Wi-Fi only); independent of the podcasts' switch. */
    val bookMobileData: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_MOBILE] ?: false }

    suspend fun setBookMobileData(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_MOBILE] = enabled }
    }

    /**
     * Ask before streaming a book or episode over mobile data (default: no, as before); applies to playback started
     * from the app, not from the watch.
     */
    val askBeforeMobileStreaming: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_ASK_MOBILE_STREAM] ?: false }

    suspend fun setAskBeforeMobileStreaming(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_ASK_MOBILE_STREAM] = enabled }
    }

    /** Automatically download the first books from "Continue listening", Wi-Fi only ([automaticBookDownloads]; default: off). */
    val bookAutoStarted: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_AUTO_STARTED] ?: false }

    suspend fun setBookAutoStarted(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_AUTO_STARTED] = enabled }
    }

    /** Automatically download the next volume of a series, Wi-Fi only ([automaticBookDownloads]; default: off). */
    val bookAutoNextInSeries: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_AUTO_NEXT] ?: false }

    suspend fun setBookAutoNextInSeries(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_AUTO_NEXT] = enabled }
    }

    /**
     * Download books from ARD Sounds in the library that expire soon, on Wi-Fi ([automaticBookDownloads], [ARD_EXPIRY_LOAD_DAYS] days
     * before; default: on – otherwise a book one wanted to keep would disappear when it expires on ARD Sounds).
     */
    val bookLoadExpiring: Flow<Boolean> = context.spokenSettingsDataStore.data.map { it[KEY_BOOK_LOAD_EXPIRING] ?: true }

    suspend fun setBookLoadExpiring(enabled: Boolean) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_LOAD_EXPIRING] = enabled }
    }

    /**
     * After how many days the download of a finished book is deleted: as with episodes 0 = immediately, a number of days
     * or [PodcastSettings.DELETE_HEARD_NEVER] (default); the choices as there ([PodcastSettings.DELETE_HEARD_CHOICES]).
     */
    val bookDeleteFinishedDays: Flow<Int> =
        context.spokenSettingsDataStore.data.map { it[KEY_BOOK_DELETE_FINISHED] ?: PodcastSettings.DELETE_HEARD_NEVER }

    suspend fun setBookDeleteFinishedDays(days: Int) {
        context.spokenSettingsDataStore.edit { it[KEY_BOOK_DELETE_FINISHED] = days.coerceAtLeast(PodcastSettings.DELETE_HEARD_NEVER) }
    }

    /** Order and filter of the books' "All" tab (default: by title, all). */
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

    /** Genre filter of the "All" tab (`books_improvements.md` S9); `null` = all genres. */
    val bookGenre: Flow<String?> = context.spokenSettingsDataStore.data.map { prefs -> prefs[KEY_BOOK_GENRE]?.ifEmpty { null } }

    suspend fun setBookGenre(genre: String?) {
        context.spokenSettingsDataStore.edit { if (genre == null) it.remove(KEY_BOOK_GENRE) else it[KEY_BOOK_GENRE] = genre }
    }

    /** Library filter of the "All" tab (identifier of the Audiobookshelf library, M3); `null` = all. */
    val bookLibrary: Flow<String?> = context.spokenSettingsDataStore.data.map { prefs -> prefs[KEY_BOOK_LIBRARY]?.ifEmpty { null } }

    suspend fun setBookLibrary(libraryId: String?) {
        context.spokenSettingsDataStore.edit { if (libraryId == null) it.remove(KEY_BOOK_LIBRARY) else it[KEY_BOOK_LIBRARY] = libraryId }
    }

    /** Order of the "Series" and "Authors" tabs (default: by name). */
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
        // The disabled ones are stored so that a new section is enabled automatically.
        val KEY_HIDDEN_AREAS = stringSetPreferencesKey("hidden_areas")
        val KEY_SEARCH_ALL = booleanPreferencesKey("search_all")
    }
}

/** The enabled sections for the stored disabled ones [hidden]; if all are off (only possible from outside), all. */
internal fun enabledAreas(hidden: Set<String>?): List<SpokenMode> =
    SpokenMode.entries.filter { it.name !in hidden.orEmpty() }.ifEmpty { SpokenMode.entries }

/** The shown section for the stored name [saved]: itself if enabled, otherwise the first of [areas]. */
internal fun shownMode(saved: String?, areas: List<SpokenMode>): SpokenMode {
    // "SPOKEN" was the mode's name before it was split into podcasts and books.
    val mode = saved?.let { name -> if (name == "SPOKEN") SpokenMode.BOOKS else SpokenMode.entries.firstOrNull { it.name == name } }
        ?: SpokenMode.MUSIC
    return mode.takeIf { it in areas } ?: areas.first()
}

/** The disabled sections after [area] has been enabled or disabled; `null` if otherwise none would be on anymore. */
internal fun hiddenAfter(hidden: Set<String>, area: SpokenMode, enabled: Boolean): Set<String>? {
    val next = if (enabled) hidden - area.name else hidden + area.name
    return next.takeIf { names -> SpokenMode.entries.any { it.name !in names } }
}
