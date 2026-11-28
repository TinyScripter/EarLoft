package de.kleinabersmart.earloft.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.wear.MirroredServer
import de.kleinabersmart.earloft.core.data.wear.WatchSpoken
import de.kleinabersmart.earloft.core.data.wear.WearSelectionRepository
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearManifest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the DataItems for the watch up to date (Wear concept 4): manifest, episodes and books (16.1/16.2), servers and settings. Runs
 * as long as the app process lives – only then can anything change. Without a watch or Data Layer the
 * DataItems only live locally with Play services.
 */
@Singleton
class WearPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val selections: WearSelectionRepository,
    private val packets: WatchPackets,
    private val servers: WearServerMirror,
    private val watchSettings: WatchSettingsRepository,
    private val replayGainSettings: ReplayGainSettings,
    private val watchSpoken: WatchSpoken,
    private val audiobookshelf: AudiobookshelfServers,
    private val spokenSettings: SpokenSettings,
    private val userDataBackup: UserDataBackup,
) {
    /** Last written manifest; `null` = still check with the Data Layer. */
    private var publishedHash: String? = null

    /** Like [publishedHash], for the episode manifest. */
    private var publishedPodcastsHash: String? = null

    /**
     * Only after the restore at startup (review5 P1): on the first start of a new installation the database is still empty, and
     * the watch would take that for the wanted state – deleting servers, downloads, episodes and books.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            userDataBackup.awaitRestore()
            publish(this)
        }
    }

    @OptIn(FlowPreview::class)
    private fun publish(scope: CoroutineScope) {
        // History and progress the watch left while the phone app wasn't listening; while the restore holds the watch, packets
        // with songs not known here yet stay, and come in once it lets go.
        scope.launch { userDataBackup.holdsWatch.collect { hold -> if (!hold) packets.catchUp() } }
        scope.launch {
            // Every finished download on the phone changes a song's phone file, the watch's fallback when its server fails.
            // For the watch nothing else changes, yet every new manifest started its process and re-planned everything (up
            // to 300 times for an album selection, review G12): such changes only go out after a quiet period.
            var lastStructure: WearManifest? = null
            selections.content()
                .map { WearManifestBuilder.build(it, context.getString(R.string.favorites_title)) }
                .unlessHeld()
                .debounce { manifest -> if (manifest.withoutPhoneFiles() == lastStructure) PHONE_FILE_DEBOUNCE_MS else 0L }
                .collect { manifest ->
                    lastStructure = manifest.withoutPhoneFiles()
                    publishManifest(manifest)
                }
        }
        scope.launch {
            // While the phone plays an episode, it writes the progress every 15 s: pure progress changes only go out
            // after a quiet period, otherwise each would wake the watch. Everything else (episodes, order, downloads) soon.
            var lastStructure: WearPodcastManifest? = null
            watchSpoken.content()
                .map(WearPodcastManifestBuilder::build)
                .unlessHeld()
                .debounce { manifest ->
                    if (WearPodcastManifestBuilder.withoutProgress(manifest) == lastStructure) PROGRESS_DEBOUNCE_MS else CHANGE_DEBOUNCE_MS
                }
                .collect { manifest ->
                    lastStructure = WearPodcastManifestBuilder.withoutProgress(manifest)
                    publishPodcasts(manifest)
                }
        }
        scope.launch {
            // The time the database was created: a new one makes the watch hold back removals (review5 P1).
            val databaseId = userDataBackup.databaseId()
            combine(servers.servers(), audiobookshelf.mirrored) { music, spoken ->
                val (subsonic, other) = music.partition { it.type == SourceType.SUBSONIC }
                WearServersDto(
                    servers = subsonic.map { it.toDto() },
                    otherServers = other.map { it.toDto() },
                    spokenServers = spoken.map { WearSpokenServerDto(it.serverId, it.name, it.baseUrl, it.credentialsStamp) },
                    databaseId = databaseId,
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

    /** Nothing while the restore holds the watch ([UserDataBackup.holdsWatch]); then the current state. */
    private fun <T : Any> Flow<T>.unlessHeld(): Flow<T> =
        combine(this, userDataBackup.holdsWatch) { value, hold -> value.takeUnless { hold } }.filterNotNull()

    private fun WearManifest.withoutPhoneFiles() = copy(songs = songs.map { it.copy(phoneFile = null) })

    private fun MirroredServer.toDto() = WearServerDto(sourceId, type.name, name, baseUrl, username, priority, formPost, credentialsStamp)

    /** Only when the content changed – otherwise every sync on the phone would trigger work on the watch. */
    private suspend fun publishManifest(manifest: WearManifest) {
        val hash = WearCodec.manifestHash(manifest)
        if (publishedHash == null) publishedHash = dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()
        if (hash == publishedHash) return
        if (dataClient.putAssetPayload(WearPaths.MANIFEST, WearCodec.encodeManifest(manifest), hash)) {
            publishedHash = hash
            Log.d(TAG, "Manifest: ${manifest.selections.size} selections, ${manifest.songs.size} songs")
        }
    }

    private suspend fun publishPodcasts(manifest: WearPodcastManifest) {
        val bytes = WearCodec.encode(manifest)
        val hash = WearCodec.hash(bytes)
        if (publishedPodcastsHash == null) publishedPodcastsHash = dataClient.hashesAt(WearPaths.PODCASTS).values.firstOrNull()
        if (hash == publishedPodcastsHash) return
        if (dataClient.putAssetPayload(WearPaths.PODCASTS, WearCodec.encodeCompressed(manifest), hash)) {
            publishedPodcastsHash = hash
            Log.d(TAG, "For the watch: ${manifest.episodes.size} episodes, ${manifest.books.size} books")
        }
    }

    /** Skip intervals and defaults for speed and effects per area, as the watch adopts them. */
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
        const val PHONE_FILE_DEBOUNCE_MS = 5 * 60_000L
    }
}
