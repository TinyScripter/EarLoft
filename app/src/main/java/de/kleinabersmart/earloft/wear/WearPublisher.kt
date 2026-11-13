package de.kleinabersmart.earloft.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.wear.MirroredServer
import de.kleinabersmart.earloft.core.data.wear.WatchSpoken
import de.kleinabersmart.earloft.core.data.wear.WearContent
import de.kleinabersmart.earloft.core.data.wear.WearSelectionRepository
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest
import de.kleinabersmart.earloft.core.wearsync.WearServerDto
import de.kleinabersmart.earloft.core.wearsync.WearServersDto
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import de.kleinabersmart.earloft.core.wearsync.WearSpokenServerDto
import de.kleinabersmart.earloft.core.wearsync.hashesAt
import de.kleinabersmart.earloft.core.wearsync.putAssetPayload
import de.kleinabersmart.earloft.core.wearsync.putPayload
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
            combine(servers.servers(), audiobookshelf.mirrored) { music, spoken ->
                val (subsonic, other) = music.partition { it.type == SourceType.SUBSONIC }
                WearServersDto(
                    servers = subsonic.map { it.toDto() },
                    otherServers = other.map { it.toDto() },
                    spokenServers = spoken.map { WearSpokenServerDto(it.serverId, it.name, it.baseUrl, it.credentialsStamp) },
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
                combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, spokenSettings.bookEffects, spokenSettings.podcastEffects, ::SpokenPlayback),
            ) { watch, mode, preAmp, areas, (bookSeek, podcastSeek, bookEffects, podcastEffects) ->
                WearSettingsDto(
                    quality = watch.quality,
                    storageLimitBytes = watch.storageLimitGb?.let { it * BYTES_PER_GB },
                    requireCharging = watch.requireCharging,
                    replayGainMode = mode,
                    preAmpDb = preAmp,
                    areas = areas,
                    bookSeekBackSeconds = bookSeek.backSeconds,
                    bookSeekForwardSeconds = bookSeek.forwardSeconds,
                    podcastSeekBackSeconds = podcastSeek.backSeconds,
                    podcastSeekForwardSeconds = podcastSeek.forwardSeconds,
                    bookSpeed = bookEffects.speed,
                    bookSkipSilence = bookEffects.skipSilence,
                    bookVolumeBoostDb = bookEffects.volumeBoostDb,
                    podcastSpeed = podcastEffects.speed,
                    podcastSkipSilence = podcastEffects.skipSilence,
                    podcastVolumeBoostDb = podcastEffects.volumeBoostDb,
                )
            }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.SETTINGS, WearCodec.encode(it)) }
        }
    }

    private fun MirroredServer.toDto() = WearServerDto(sourceId, type.name, name, baseUrl, username, priority, formPost, credentialsStamp)

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

    /** Sprungweiten und Vorgaben für Tempo und Effekte je Bereich, wie sie die Uhr übernimmt. */
    private data class SpokenPlayback(
        val bookSeek: SeekSteps,
        val podcastSeek: SeekSteps,
        val bookEffects: SpokenEffects,
        val podcastEffects: SpokenEffects,
    )

    private companion object {
        const val TAG = "WearPublisher"
        const val BYTES_PER_GB = 1_000_000_000L
        const val CHANGE_DEBOUNCE_MS = 2_000L
        const val PROGRESS_DEBOUNCE_MS = 30_000L
    }
}
