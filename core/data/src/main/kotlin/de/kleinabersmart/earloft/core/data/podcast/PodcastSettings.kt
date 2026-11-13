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
    /** Abstand der automatischen Aktualisierung in Stunden; 0 = nur von Hand. */
    val refreshHours: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_REFRESH_HOURS] ?: DEFAULT_REFRESH_HOURS }

    suspend fun setRefreshHours(hours: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_REFRESH_HOURS] = hours.coerceAtLeast(0) }
    }

    /** Auch über Mobilfunk laden (Standard: nein, nur WLAN). */
    val useMobileData: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_MOBILE] ?: false }

    suspend fun setUseMobileData(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_MOBILE] = enabled }
    }

    /**
     * Nach wie vielen Tagen der Download einer zu Ende gehörten Folge gelöscht wird: [DELETE_HEARD_IMMEDIATELY] (0),
     * eine Anzahl Tage oder [DELETE_HEARD_NEVER] (-1).
     */
    val deleteHeardAfterDays: Flow<Int> =
        context.podcastSettingsDataStore.data.map { it[KEY_DELETE_HEARD_DAYS] ?: DEFAULT_DELETE_HEARD_DAYS }

    suspend fun setDeleteHeardAfterDays(days: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_DELETE_HEARD_DAYS] = days.coerceAtLeast(DELETE_HEARD_NEVER) }
    }

    /**
     * Vorgabe für neue Abos: Neue Folgen automatisch herunterladen (Standard: aus). Gilt nur beim Abonnieren; die
     * Einstellung bestehender Abos bleibt, wenn sie sich ändert.
     */
    val autoDownloadNew: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_NEW] ?: false }

    suspend fun setAutoDownloadNew(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_NEW] = enabled }
    }

    /** Wie viele der neuesten ungehörten Folgen die Download-Automatik je Podcast bereithält. */
    val autoDownloadCount: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_COUNT] ?: DEFAULT_AUTO_DOWNLOAD_COUNT }

    suspend fun setAutoDownloadCount(count: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_COUNT] = count.coerceAtLeast(1) }
    }

    /** Ist eine Folge zu Ende, startet die nächste der Zeile „Weiterhören“ (Standard: an). */
    val autoPlayNext: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_PLAY_NEXT] = enabled }
    }

    /** Reihenfolge der Abos auf Start und im Reiter „Abos“ (Standard: nach Name). */
    val feedSort: Flow<FeedSort> = context.podcastSettingsDataStore.data.map { prefs ->
        FeedSort.entries.firstOrNull { it.name == prefs[KEY_FEED_SORT] } ?: FeedSort.NAME
    }

    suspend fun setFeedSort(sort: FeedSort) {
        context.podcastSettingsDataStore.edit { it[KEY_FEED_SORT] = sort.name }
    }

    /** In diesen Verzeichnissen sucht die Seite „Podcast hinzufügen“ (Standard: in keinem, Konzept 12.10). */
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
     * Land für die Suche bei Apple Podcasts (ISO 3166, z. B. `DE`). Beim ersten Start aus den Sprachen von App und System
     * gesetzt ([rememberAppleCountry]); bis dahin dasselbe, nur ungespeichert.
     */
    val appleCountry: Flow<String> = context.podcastSettingsDataStore.data.map { it[KEY_APPLE_COUNTRY] ?: deviceCountry() }

    suspend fun setAppleCountry(country: String) {
        context.podcastSettingsDataStore.edit { it[KEY_APPLE_COUNTRY] = country }
    }

    /**
     * Adresse der RSS-Bridge, über die der OPML-Export Sendungen aus ARD Sounds als Feed nennt (Konzept 12.10); leer = sie
     * fehlen im Export. Voreingestellt ist [DEFAULT_RSS_BRIDGE].
     */
    val rssBridge: Flow<String> = context.podcastSettingsDataStore.data.map { it[KEY_RSS_BRIDGE] ?: DEFAULT_RSS_BRIDGE }

    suspend fun setRssBridge(url: String) {
        context.podcastSettingsDataStore.edit { it[KEY_RSS_BRIDGE] = url.trim() }
    }

    /** Beim ersten Start: Speichert das Land aus den Spracheinstellungen, solange noch keins gewählt ist. */
    suspend fun rememberAppleCountry() {
        context.podcastSettingsDataStore.edit { if (it[KEY_APPLE_COUNTRY] == null) it[KEY_APPLE_COUNTRY] = deviceCountry() }
    }

    /** Land aus der Sprache der App (Android 13+, Einstellungen des Systems je App) und den Sprachen des Systems. */
    private fun deviceCountry(): String {
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toList().orEmpty()
        } else {
            emptyList()
        }
        return countryFromLocales(app, Resources.getSystem().configuration.locales.toList())
    }

    companion object {
        /** Auswahl in den Einstellungen; das Land des Systems kommt dazu, wenn es fehlt. */
        val APPLE_COUNTRY_CHOICES = listOf(
            "DE", "AT", "CH", "LU", "LI", "BE", "NL", "DK", "PL", "CZ", "FR", "IT", "ES", "PT",
            "SE", "NO", "FI", "GB", "IE", "US", "CA", "AU", "NZ",
        )

        /**
         * Land zu den Sprachen von App ([app], leer = wie das System) und System ([system], in Reihenfolge der Vorliebe):
         * Nennt die Sprache der App eine Region, gilt sie. Sonst die erste Systemsprache derselben Sprache mit Region
         * (App Deutsch, System `de-AT` → Österreich), sonst das übliche Land der App-Sprache (Deutsch → Deutschland).
         * Ohne eigene App-Sprache die erste Systemsprache mit Region, sonst das übliche Land ihrer Sprache. Zuletzt die
         * USA, Apples Standard.
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

        /** Nur Länder (zwei Buchstaben), keine Gebiete wie `419` (Lateinamerika). */
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

        /** Auswahl in den Einstellungen. */
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
    }
}
