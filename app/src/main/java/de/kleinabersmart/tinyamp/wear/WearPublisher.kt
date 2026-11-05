package de.kleinabersmart.tinyamp.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.tinyamp.core.data.spoken.SpokenSettings
import de.kleinabersmart.tinyamp.core.data.wear.WatchSpoken
import de.kleinabersmart.tinyamp.core.data.wear.WearContent
import de.kleinabersmart.tinyamp.core.data.wear.WearSelectionRepository
import de.kleinabersmart.tinyamp.core.data.wear.WearServerMirror
import de.kleinabersmart.tinyamp.core.playback.ReplayGainSettings
import de.kleinabersmart.tinyamp.core.wearsync.WearCodec
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.WearPodcastManifest
import de.kleinabersmart.tinyamp.core.wearsync.WearServerDto
import de.kleinabersmart.tinyamp.core.wearsync.WearServersDto
import de.kleinabersmart.tinyamp.core.wearsync.WearSettingsDto
import de.kleinabersmart.tinyamp.core.wearsync.WearSpokenServerDto
import de.kleinabersmart.tinyamp.core.wearsync.hashesAt
import de.kleinabersmart.tinyamp.core.wearsync.putAssetPayload
import de.kleinabersmart.tinyamp.core.wearsync.putPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die DataItems für die Uhr aktuell (Wear-Konzept 4): Manifest, Folgen und Bücher (16.1/16.2), Server und Einstellungen. Läuft,
 * solange der App-Prozess lebt – nur dann kann sich etwas ändern. Ohne Uhr bzw. Data Layer liegen die
 * DataItems nur lokal bei den Play-Diensten.
 */
@Singleton
class WearPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val selections: WearSelectionRepository,
    private val servers: WearServerMirror,
    private val watchSettings: WatchSettingsRepository,
    private val replayGainSettings: ReplayGainSettings,
    private val watchSpoken: WatchSpoken,
    private val audiobookshelf: AudiobookshelfServers,
    private val spokenSettings: SpokenSettings,
) {
    /** Zuletzt geschriebenes Manifest; `null` = noch beim Data Layer nachsehen. */
    private var publishedHash: String? = null

    /** Wie [publishedHash], für das Folgen-Manifest. */
    private var publishedPodcastsHash: String? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch { selections.content().collect { publishManifest(it) } }
        scope.launch {
            // Während das Handy eine Folge spielt, schreibt es alle 15 s den Fortschritt: Reine Fortschrittsänderungen gehen erst
            // nach einer Ruhepause hinaus, sonst weckte jede die Uhr. Alles andere (Folgen, Reihenfolge, Downloads) bald.
            var lastStructure: WearPodcastManifest? = null
            watchSpoken.content()
                .map(WearPodcastManifestBuilder::build)
                .debounce { manifest ->
                    if (WearPodcastManifestBuilder.withoutProgress(manifest) == lastStructure) PROGRESS_DEBOUNCE_MS else CHANGE_DEBOUNCE_MS
                }
                .collect { manifest ->
                    lastStructure = WearPodcastManifestBuilder.withoutProgress(manifest)
                    publishPodcasts(manifest)
                }
        }
        scope.launch {
            combine(servers.servers(), audiobookshelf.servers) { music, spoken ->
                WearServersDto(
                    servers = music.map { WearServerDto(it.sourceId, it.name, it.baseUrl, it.username, it.priority, it.formPost) },
                    // Ausgeschaltete Server fehlen: Ihre Bücher sind am Handy ausgeblendet und fallen von der Uhr.
                    spokenServers = spoken.filter { it.enabled }.map { WearSpokenServerDto(it.serverId, it.name, it.baseUrl) },
                )
            }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.SERVERS, WearCodec.encode(it)) }
        }
        scope.launch {
            combine(
                watchSettings.settings,
                replayGainSettings.mode,
                replayGainSettings.preAmpDb,
                spokenSettings.areas,
            ) { watch, mode, preAmp, areas ->
                WearSettingsDto(
                    quality = watch.quality,
                    storageLimitBytes = watch.storageLimitGb?.let { it * BYTES_PER_GB },
                    requireCharging = watch.requireCharging,
                    replayGainMode = mode,
                    preAmpDb = preAmp,
                    areas = areas,
                )
            }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.SETTINGS, WearCodec.encode(it)) }
        }
    }

    /** Nur bei geändertem Inhalt – sonst löste jeder Abgleich am Handy Arbeit auf der Uhr aus. */
    private suspend fun publishManifest(content: WearContent) {
        val manifest = WearManifestBuilder.build(content, context.getString(R.string.favorites_title))
        val hash = WearCodec.manifestHash(manifest)
        if (publishedHash == null) publishedHash = dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()
        if (hash == publishedHash) return
        if (dataClient.putAssetPayload(WearPaths.MANIFEST, WearCodec.encodeManifest(manifest), hash)) {
            publishedHash = hash
            Log.d(TAG, "Manifest: ${manifest.selections.size} Auswahlen, ${manifest.songs.size} Songs")
        }
    }

    private suspend fun publishPodcasts(manifest: WearPodcastManifest) {
        val bytes = WearCodec.encode(manifest)
        val hash = WearCodec.hash(bytes)
        if (publishedPodcastsHash == null) publishedPodcastsHash = dataClient.hashesAt(WearPaths.PODCASTS).values.firstOrNull()
        if (hash == publishedPodcastsHash) return
        if (dataClient.putAssetPayload(WearPaths.PODCASTS, WearCodec.encodeCompressed(manifest), hash)) {
            publishedPodcastsHash = hash
            Log.d(TAG, "Für die Uhr: ${manifest.episodes.size} Folgen, ${manifest.books.size} Bücher")
        }
    }

    private companion object {
        const val TAG = "WearPublisher"
        const val BYTES_PER_GB = 1_000_000_000L
        const val CHANGE_DEBOUNCE_MS = 2_000L
        const val PROGRESS_DEBOUNCE_MS = 30_000L
    }
}
