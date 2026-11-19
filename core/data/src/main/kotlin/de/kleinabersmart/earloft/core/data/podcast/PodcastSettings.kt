package de.kleinabersmart.earloft.core.data.podcast

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.model.FeedSort
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.DEFAULT_RSS_BRIDGE
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.podcastSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "podcast_settings")

@Singleton
class PodcastSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Interval of automatic refresh in hours; 0 = manual only. */
    val refreshHours: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_REFRESH_HOURS] ?: DEFAULT_REFRESH_HOURS }

    suspend fun setRefreshHours(hours: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_REFRESH_HOURS] = hours.coerceAtLeast(0) }
    }

    /** Also download over mobile data (default: no, Wi-Fi only). */
    val useMobileData: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_MOBILE] ?: false }

    suspend fun setUseMobileData(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_MOBILE] = enabled }
    }

    /**
     * After how many days the download of an episode played to the end is deleted: [DELETE_HEARD_IMMEDIATELY] (0),
     * a number of days or [DELETE_HEARD_NEVER] (-1).
     */
    val deleteHeardAfterDays: Flow<Int> =
        context.podcastSettingsDataStore.data.map { it[KEY_DELETE_HEARD_DAYS] ?: DEFAULT_DELETE_HEARD_DAYS }

    suspend fun setDeleteHeardAfterDays(days: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_DELETE_HEARD_DAYS] = days.coerceAtLeast(DELETE_HEARD_NEVER) }
    }

    /**
     * Default for new subscriptions: download new episodes automatically (default: off). Only applies when subscribing; the
     * setting of existing subscriptions stays when it changes.
     */
    val autoDownloadNew: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_NEW] ?: false }

    suspend fun setAutoDownloadNew(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_NEW] = enabled }
    }

    /** How many of the newest unplayed episodes the download automation keeps ready per podcast. */
    val autoDownloadCount: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_COUNT] ?: DEFAULT_AUTO_DOWNLOAD_COUNT }

    suspend fun setAutoDownloadCount(count: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_COUNT] = count.coerceAtLeast(1) }
    }

    /** When an episode ends, the next one in the "Continue listening" row starts (default: on). */
    val autoPlayNext: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_PLAY_NEXT] = enabled }
    }

    /**
     * Swiping on episode rows (concept 12.5 "Multi-select and swiping"): right adds to the queue, left
     * marks as played (default: on).
     */
    val swipeActions: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_SWIPE_ACTIONS] ?: true }

    suspend fun setSwipeActions(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_SWIPE_ACTIONS] = enabled }
    }

    /** Order of subscriptions on Home and in the "Subscriptions" tab (default: by name). */
    val feedSort: Flow<FeedSort> = context.podcastSettingsDataStore.data.map { prefs ->
        FeedSort.entries.firstOrNull { it.name == prefs[KEY_FEED_SORT] } ?: FeedSort.NAME
    }

    suspend fun setFeedSort(sort: FeedSort) {
        context.podcastSettingsDataStore.edit { it[KEY_FEED_SORT] = sort.name }
    }

    /** The "Add podcast" page searches in these directories (default: none, concept 12.10). */
    val directories: Flow<Set<PodcastDirectory>> = context.podcastSettingsDataStore.data.map { prefs ->
        prefs[KEY_DIRECTORIES].orEmpty().mapNotNullTo(HashSet()) { name -> PodcastDirectory.entries.firstOrNull { it.name == name } }
    }

    suspend fun setDirectory(directory: PodcastDirectory, enabled: Boolean) {
        context.podcastSettingsDataStore.edit {
            val current = it[KEY_DIRECTORIES].orEmpty()
            it[KEY_DIRECTORIES] = if (enabled) current + directory.name else current - directory.name
        }
    }

    /**
     * Country for the Apple Podcasts search (ISO 3166, e.g. `DE`). Set on first start from the app and system
     * languages ([rememberAppleCountry]); until then the same, just not stored.
     */
    val appleCountry: Flow<String> = context.podcastSettingsDataStore.data.map { it[KEY_APPLE_COUNTRY] ?: deviceCountry() }

    suspend fun setAppleCountry(country: String) {
        context.podcastSettingsDataStore.edit { it[KEY_APPLE_COUNTRY] = country }
    }

    /**
     * Address of the RSS bridge through which the OPML export lists shows from ARD Sounds as a feed (concept 12.10); empty = they
     * are missing from the export. Preset to [DEFAULT_RSS_BRIDGE].
     */
    val rssBridge: Flow<String> = context.podcastSettingsDataStore.data.map { it[KEY_RSS_BRIDGE] ?: DEFAULT_RSS_BRIDGE }

    suspend fun setRssBridge(url: String) {
        context.podcastSettingsDataStore.edit { it[KEY_RSS_BRIDGE] = url.trim() }
    }

    /** On first start: stores the country from the language settings, as long as none has been chosen. */
    suspend fun rememberAppleCountry() {
        context.podcastSettingsDataStore.edit { if (it[KEY_APPLE_COUNTRY] == null) it[KEY_APPLE_COUNTRY] = deviceCountry() }
    }

    /** Country from the app language (Android 13+, per-app system settings) and the system languages. */
    private fun deviceCountry(): String {
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toList().orEmpty()
        } else {
            emptyList()
        }
        return countryFromLocales(app, Resources.getSystem().configuration.locales.toList())
    }

    companion object {
        /** Selection in the settings; the system's country is added if missing. */
        val APPLE_COUNTRY_CHOICES = listOf(
            "DE", "AT", "CH", "LU", "LI", "BE", "NL", "DK", "PL", "CZ", "FR", "IT", "ES", "PT",
            "SE", "NO", "FI", "GB", "IE", "US", "CA", "AU", "NZ",
        )

        /**
         * Country for the languages of the app ([app], empty = same as system) and system ([system], in order of preference):
         * if the app language names a region, it applies. Otherwise the first system language of the same language with a region
         * (app German, system `de-AT` → Austria), otherwise the usual country of the app language (German → Germany).
         * Without a dedicated app language, the first system language with a region, otherwise the usual country of its language. Finally the
         * USA, Apple's default.
         */
        fun countryFromLocales(app: List<Locale>, system: List<Locale>): String {
            app.firstOrNull()?.let { language ->
                return language.region()
                    ?: system.firstNotNullOfOrNull { it.takeIf { it.language == language.language }?.region() }
                    ?: language.likelyRegion()
                    ?: DEFAULT_COUNTRY
            }
            return system.firstNotNullOfOrNull { it.region() } ?: system.firstNotNullOfOrNull { it.likelyRegion() } ?: DEFAULT_COUNTRY
        }

        private const val DEFAULT_COUNTRY = "US"

        /** Only countries (two letters), no regions like `419` (Latin America). */
        private fun Locale.region(): String? = country.takeIf { it.length == 2 }?.uppercase()

        private fun Locale.likelyRegion(): String? =
            language.takeIf { it.isNotEmpty() }?.let { ULocale.addLikelySubtags(ULocale(it)).country }?.takeIf { it.length == 2 }

        private fun LocaleList.toList(): List<Locale> = (0 until size()).map(::get)

        private val KEY_DIRECTORIES = stringSetPreferencesKey("directories")
        private val KEY_APPLE_COUNTRY = stringPreferencesKey("apple_country")
        private val KEY_RSS_BRIDGE = stringPreferencesKey("rss_bridge")
        private val KEY_FEED_SORT = stringPreferencesKey("feed_sort")

        val AUTO_DOWNLOAD_COUNT_CHOICES = listOf(1, 2, 3, 5, 10)
        const val DEFAULT_AUTO_DOWNLOAD_COUNT = 3

        private val KEY_AUTO_COUNT = intPreferencesKey("auto_download_count")
        private val KEY_AUTO_NEW = booleanPreferencesKey("auto_download_new")

        /** Selection in the settings. */
        val REFRESH_HOUR_CHOICES = listOf(0, 1, 3, 6, 12, 24)
        const val DEFAULT_REFRESH_HOURS = 6

        const val DELETE_HEARD_NEVER = -1
        const val DELETE_HEARD_IMMEDIATELY = 0
        val DELETE_HEARD_CHOICES = listOf(DELETE_HEARD_IMMEDIATELY, 1, 3, 7, 14, 30, DELETE_HEARD_NEVER)
        const val DEFAULT_DELETE_HEARD_DAYS = 3

        private val KEY_DELETE_HEARD_DAYS = intPreferencesKey("delete_heard_days")
        private val KEY_REFRESH_HOURS = intPreferencesKey("refresh_hours")
        private val KEY_MOBILE = booleanPreferencesKey("mobile_data")
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val KEY_SWIPE_ACTIONS = booleanPreferencesKey("swipe_actions")
    }
}
