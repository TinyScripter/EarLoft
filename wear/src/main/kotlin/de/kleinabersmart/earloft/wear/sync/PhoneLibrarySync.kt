package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.MirroredAbsServer
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.wear.MirroredServer
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearManifest
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest
import de.kleinabersmart.earloft.core.wearsync.WearServerDto
import de.kleinabersmart.earloft.core.wearsync.WearServersDto
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import de.kleinabersmart.earloft.core.wearsync.assetPayloadsAt
import de.kleinabersmart.earloft.core.wearsync.hashesAt
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.download.WearDownloadPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the phone's state to the watch's database (Wear concept 6/7): first the servers (including those of Audiobookshelf,
 * 16.2), then the manifest, then episodes and books (16.1/16.2). Always reads the current state of the DataItems – running it
 * several times does no harm.
 */
@Singleton
class PhoneLibrarySync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val credentials: CredentialsRequester,
    private val serverMirror: WearServerMirror,
    private val audiobookshelf: AudiobookshelfServers,
    private val manifestApplier: ManifestApplier,
    private val podcastMirror: PodcastMirror,
    private val episodeReporter: WatchEpisodeReporter,
    private val episodeQueue: EpisodeQueue,
    private val state: WatchState,
    private val planner: WearDownloadPlanner,
    private val phoneConnection: PhoneConnection,
    private val phoneReturn: PhoneReturn,
) {
    private val mutex = Mutex()

    /**
     * Changes with every update of the watch app: a new version may understand more of the same state (e.g. servers
     * of a new type in `otherServers`) that the old one had already marked as applied.
     */
    private val appVersionStamp: Long by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }

    suspend fun sync() = mutex.withLock {
        // Settings first: quality and storage limit determine what the planner queues. If this watch does not understand them
        // (newer value from the phone), the previous ones apply – servers and manifest still come. Changed skip intervals
        // alone do not replan: that would replace the download job and cancel a running download.
        val previous = state.settings.value
        val settingsChanged = dataClient.payloadsAt(WearPaths.SETTINGS).values.firstOrNull()
            ?.let { bytes -> decodeOrNull<WearSettingsDto>(bytes, "Einstellungen") }
            ?.takeIf { state.updateSettings(it) }
            ?.let { it.withoutSeek() != previous.withoutSeek() } == true
        val servers = dataClient.payloadsAt(WearPaths.SERVERS).entries.firstOrNull()
        // Every process start of the watch triggers a run (message from the phone, download attempt, tile …). If nothing new
        // has arrived since the last apply, it stops at asking for passwords and planning.
        val podcastsHash = dataClient.hashesAt(WearPaths.PODCASTS).values.firstOrNull()
        val manifestHash = dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()
        val stamp = "$appVersionStamp:${servers?.value?.let(WearCodec::hash)}:$manifestHash:$podcastsHash"
        val phoneBack = phoneIsBack()
        if (!settingsChanged && stamp == state.appliedPhoneStamp) {
            servers?.let { requestMissingPasswords(phoneNodeId = it.key) }
            planner.plan(replaceSchedule = phoneBack)
            return@withLock
        }
        if (servers != null) {
            val dto = WearCodec.decode<WearServersDto>(servers.value)
            val renewed = serverMirror.apply((dto.servers + dto.otherServers).mapNotNull { it.toMirrored() }) +
                audiobookshelf.mirror(dto.spokenServers.map { MirroredAbsServer(it.serverId, it.name, it.baseUrl, it.credentialsStamp) })
            // Fetch credentials changed on the phone right away, not only after the waiting time for missing ones.
            credentials.request(renewed, phoneNodeId = servers.key)
            requestMissingPasswords(phoneNodeId = servers.key)
        }
        val manifest = dataClient.assetPayloadsAt(WearPaths.MANIFEST).values.firstOrNull()
        manifest?.let { manifestApplier.apply(WearCodec.decodeManifest(it)) }
        val podcasts = dataClient.assetPayloadsAt(WearPaths.PODCASTS).values.firstOrNull()
        if (podcasts != null) {
            // Report the positions first: whatever the new manifest no longer contains is deleted by the mirror along with its progress.
            episodeReporter.report()
            episodeQueue.setOrder(podcastMirror.apply(WearCodec.decodeCompressed<WearPodcastManifest>(podcasts)))
        }
        planner.plan(replaceSchedule = settingsChanged || phoneBack)
        // A DataItem whose asset was not readable (yet) (phone out of range, transfer running) does not count as
        // applied; otherwise it would only come with the next change on the phone.
        if ((manifestHash == null || manifest != null) && (podcastsHash == null || podcasts != null)) {
            state.appliedPhoneStamp = stamp
        }
    }

    /**
     * If the download job was waiting for the charger because the phone was missing several times, and it is reachable now: full
     * attempts again, and the job runs right away instead of only on the charger (Review V1).
     */
    private suspend fun phoneIsBack(): Boolean {
        if (!state.waitingForPhone || phoneConnection.connectedPhoneNodeId() == null) return false
        state.clearPhoneUnreachable()
        phoneReturn.withdraw()
        return true
    }

    /**
     * Asks the phone for passwords and API keys the watch lacks (new server, watch reset). The
     * answer arrives as a message at the `WatchListenerService`. If the phone has none itself, no answer would come, and every start
     * of the watch would wake the phone again; hence at most every [CredentialsRequester.MISSING_RETRY_MS] per source.
     */
    private suspend fun requestMissingPasswords(phoneNodeId: String) {
        credentials.requestMissing(serverMirror.serversWithoutPassword() + audiobookshelf.withoutKey(), phoneNodeId)
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray, what: String): T? =
        try {
            WearCodec.decode<T>(bytes)
        } catch (e: SerializationException) {
            Log.w(TAG, "$what des Handys nicht lesbar (neuere App-Version?): ${e.message}")
            null
        }

    /** `null` for types this watch version does not know (yet) – it downloads their songs via the phone. */
    private fun WearServerDto.toMirrored(): MirroredServer? {
        val sourceType = SourceType.entries.firstOrNull { it.name == type && it.isServer }
        if (sourceType == null) {
            Log.w(TAG, "Server $sourceId: Typ $type wird nicht unterstützt")
            return null
        }
        return MirroredServer(sourceId, sourceType, name, baseUrl, username, priority, formPost, credentialsStamp)
    }

    private companion object {
        const val TAG = "PhoneLibrarySync"
    }
}

/**
 * Writes a manifest to the database: songs via the `LibrarySyncer` (one
 * [WearManifestSource] per source), selections as device playlists (Wear concept 7.2). In `wear_selections`
 * `refId` on the watch points to this playlist; `addedAt` is the position in the manifest (the phone's order).
 */
class ManifestApplier @Inject constructor(
    private val database: EarLoftDatabase,
    private val sourceDao: SourceDao,
    private val wearDao: WearDao,
    private val playlistDao: PlaylistDao,
    private val librarySyncer: LibrarySyncer,
) {
    suspend fun apply(manifest: WearManifest) {
        sourceDao.insertIfAbsent(SourceEntity(WearManifestSource.PHONE_SOURCE_ID, SourceType.PHONE, PHONE_SOURCE_NAME))
        val serverSources = sourceDao.getServers()
        val servers = serverSources.mapTo(HashSet()) { it.sourceId }
        val keepPhone = wearDao.doneDownloadRemoteIds(WearManifestSource.PHONE_SOURCE_ID).toSet()
        val tracks = manifestTracks(manifest, servers, keepPhone)
        // Sources without tracks too: their sync deletes whatever has dropped out of all selections.
        serverSources.forEach { librarySyncer.sync(WearManifestSource(it.sourceId, it.type, tracks[it.sourceId].orEmpty())) }
        librarySyncer.sync(
            WearManifestSource(WearManifestSource.PHONE_SOURCE_ID, SourceType.PHONE, tracks[WearManifestSource.PHONE_SOURCE_ID].orEmpty()),
        )
        applySelections(manifest, servers)
        Log.d(TAG, "Manifest übernommen: ${manifest.selections.size} Auswahlen, ${manifest.songs.size} Songs")
    }

    private suspend fun applySelections(manifest: WearManifest, servers: Set<String>) = database.withTransaction {
        val uids = manifest.songs.associate { it.key to watchSourceUids(it, servers) }
        val songIdsByUid = uids.values.flatten().chunked(MAX_SQL_VARIABLES)
            .flatMap { wearDao.getSongIdsOfSources(it) }
            .associate { it.uid to it.songId }
        val songIdsByKey = uids.mapValues { (_, songUids) -> songUids.firstNotNullOfOrNull { songIdsByUid[it] } }

        val existing = wearDao.getSelections().associateBy { it.key }
        val now = System.currentTimeMillis()
        manifest.selections.forEachIndexed { index, selection ->
            val playlistId = existing[selection.key]?.refId?.takeIf { playlistDao.getPlaylist(it) != null }
                ?.also { playlistDao.rename(it, selection.title, now) }
                ?: playlistDao.insertPlaylist(PlaylistEntity(name = selection.title, updatedAt = now))
            playlistDao.deleteEntries(playlistId)
            playlistDao.insertEntries(
                selection.songKeys.mapNotNull { songIdsByKey[it] }
                    .mapIndexed { position, songId -> PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = position) },
            )
            wearDao.upsert(WearSelectionEntity(selection.key, selection.kind, playlistId, addedAt = index.toLong()))
        }
        val removed = existing.keys - manifest.selections.mapTo(HashSet()) { it.key }
        removed.forEach { key ->
            wearDao.delete(key)
            existing.getValue(key).refId?.let { playlistDao.deletePlaylist(it) }
        }
    }

    private companion object {
        const val TAG = "ManifestApplier"
        const val PHONE_SOURCE_NAME = "Handy"
        const val MAX_SQL_VARIABLES = 900
    }
}
