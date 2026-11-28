package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.MirroredAbsServer
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.wear.MirroredServer
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
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
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.download.WearDownloadPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
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
class PhoneLibrarySync @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val phoneData: PhoneData,
    private val phone: PhoneLink,
    private val serverMirror: WearServerMirror,
    private val audiobookshelf: AudiobookshelfServers,
    private val manifestApplier: ManifestApplier,
    private val podcastMirror: PodcastMirror,
    private val episodeReporter: WatchEpisodeReporter,
    private val episodeQueue: EpisodeQueue,
    private val state: WatchState,
    private val planner: WearDownloadPlanner,
    private val removalGuard: RemovalGuard,
    private val sourceDao: SourceDao,
    private val wearDao: WearDao,
    private val bookDao: BookDao,
) {
    private val mutex = Mutex()

    /**
     * Changes with every update of the watch app: a new version may understand more of the same state (e.g. servers
     * of a new type in `otherServers`) that the old one had already marked as applied.
     */
    private val appVersionStamp: Long by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }

    /**
     * Applies what the phone published. @return `false` if a DataItem's asset was not readable (yet): it is not applied, and the caller
     * tries again; only a new DataItem or the next process start would otherwise bring it (review5 P71).
     */
    suspend fun sync(): Boolean = mutex.withLock {
        // Settings first: quality and storage limit determine what the planner queues. If this watch does not understand them
        // (newer value from the phone), the previous ones apply – servers and manifest still come. Only quality and the charger rule
        // cancel a running download ([WearSettingsDto.restartsDownloadsAfter]); that is noted until the plan ran (review5 P66).
        // Settings never apply servers and manifests again: only the plan reads them, and it runs either way. ReplayGain, pre-amp or
        // areas used to decode everything again and sync every source (review5 P67).
        val previous = state.settings.value
        val adopted = phoneData.payloads(WearPaths.SETTINGS).values.firstOrNull()
            ?.let { bytes -> decodeOrNull<WearSettingsDto>(bytes, "Settings") }
            ?.takeIf { state.updateSettings(it) }
        if (adopted?.restartsDownloadsAfter(previous) == true) state.downloadRestartPending = true
        val servers = phoneData.payloads(WearPaths.SERVERS).entries.firstOrNull()
        // Every process start of the watch triggers a run (message from the phone, download attempt, tile …). If nothing new
        // has arrived since the last apply, it stops at asking for passwords and planning.
        val podcastsHash = phoneData.hashes(WearPaths.PODCASTS).values.firstOrNull()
        val manifestHash = phoneData.hashes(WearPaths.MANIFEST).values.firstOrNull()
        val stamp = "$appVersionStamp:${servers?.value?.let(WearCodec::hash)}:$manifestHash:$podcastsHash"
        val phoneBack = phoneIsBack()
        if (stamp == state.appliedPhoneStamp) {
            // The phone is back at the state the watch has: one held in between is gone.
            removalGuard.allows(drops = false)
            servers?.let { requestMissingPasswords(phoneNodeId = it.key) }
            plan(phoneBack)
            return@withLock true
        }
        // Report the positions first: a server removed on the phone takes its books along with their progress, and whatever the
        // new episode manifest no longer contains goes the same way (review5 P63).
        episodeReporter.report()
        // Unreadable: the known servers stay (an empty list would remove them along with their songs).
        val serversDto = servers?.let { decodeOrNull<WearServersDto>(it.value, "Servers") }
        val manifest = phoneData.assetPayloads(WearPaths.MANIFEST).values.firstOrNull()
        // Each part on its own: one the watch can't read (newer phone app) must not block the others (review G29).
        val manifestDto = manifest?.let { bytes -> readOrNull("Manifest") { WearCodec.decodeManifest(bytes) } }
        val podcasts = phoneData.assetPayloads(WearPaths.PODCASTS).values.firstOrNull()
        val podcastsDto = podcasts?.let { bytes -> readOrNull("Episodes") { WearCodec.decodeCompressed<WearPodcastManifest>(bytes) } }
        // A phone that suddenly reports (almost) nothing: nothing is removed until the user confirms it or a day has passed.
        val newPhone = removalGuard.notePhoneDatabase(serversDto?.databaseId)
        if (!removalGuard.allows(drops(serversDto, manifestDto, podcastsDto, newPhone))) {
            plan(phoneBack)
            return@withLock true
        }
        if (servers != null && serversDto != null) {
            val dto = serversDto
            val renewed = serverMirror.apply((dto.servers + dto.otherServers).mapNotNull { it.toMirrored() }) +
                audiobookshelf.mirror(dto.spokenServers.map { MirroredAbsServer(it.serverId, it.name, it.baseUrl, it.credentialsStamp) })
            // Fetch credentials changed on the phone right away, not only after the waiting time for missing ones.
            phone.requestCredentials(renewed, phoneNodeId = servers.key)
            requestMissingPasswords(phoneNodeId = servers.key)
        }
        manifestDto?.let { manifestApplier.apply(it) }
        podcastsDto?.let { episodeQueue.setOrder(podcastMirror.apply(it)) }
        plan(phoneBack)
        // A DataItem whose asset was not readable (yet) (phone out of range, transfer running) does not count as
        // applied; otherwise it would only come with the next change on the phone.
        val complete = (manifestHash == null || manifest != null) && (podcastsHash == null || podcasts != null)
        if (complete) state.appliedPhoneStamp = stamp
        complete
    }

    /** Plans the downloads; a pending restart ([WatchState.downloadRestartPending]) is done with it and only then forgotten. */
    private suspend fun plan(phoneBack: Boolean) {
        val restart = state.downloadRestartPending
        planner.plan(replaceSchedule = phoneBack, restart = restart)
        if (restart) state.downloadRestartPending = false
    }

    /**
     * Whether the phone's state would remove much of what is on the watch ([RemovalGuard.drops]): all servers, more than half of
     * the selections, or of the episodes and books not listened to the end (those that are drop out of the selection on their own).
     */
    private suspend fun drops(servers: WearServersDto?, manifest: WearManifest?, podcasts: WearPodcastManifest?, newPhone: Boolean): Boolean {
        if (servers != null) {
            val incoming = (servers.servers + servers.otherServers).map { it.sourceId } + servers.spokenServers.map { it.serverId }
            val known = sourceDao.getServers().map { it.sourceId } + bookDao.servers().map { it.serverId }
            if (RemovalGuard.drops(known.size, known.count { it in incoming }, newPhone, atLeast = 1)) return true
        }
        if (manifest != null) {
            val incoming = manifest.selections.filter { it.kind != null }.mapTo(HashSet()) { it.key }
            val known = wearDao.getSelections().map { it.key }
            if (RemovalGuard.drops(known.size, known.count { it in incoming }, newPhone, atLeast = 2)) return true
        }
        if (podcasts != null) {
            val incoming = (podcasts.episodes.map { it.feedId to it.guid } + podcasts.books.map { it.serverId to it.remoteId }).toSet()
            val finished = bookDao.keyedProgress().filter { it.progress.finished }.mapTo(HashSet()) { it.serverId to it.remoteId }
            val known = bookDao.observeBookRefs().first().map { it.serverId to it.remoteId }.filter { it !in finished }
            if (RemovalGuard.drops(known.size, known.count { it in incoming }, newPhone, atLeast = 2)) return true
        }
        return false
    }

    /**
     * If the download job was waiting for the charger because the phone was missing several times, and it is reachable now: full
     * attempts again, and the job runs right away instead of only on the charger (Review V1).
     */
    private suspend fun phoneIsBack(): Boolean {
        if (!state.waitingForPhone || phone.connectedPhoneNodeId() == null) return false
        state.clearPhoneUnreachable()
        phone.withdrawReturnRequest()
        return true
    }

    /**
     * Asks the phone for passwords and API keys the watch lacks (new server, watch reset). The
     * answer arrives as a message at the `WatchListenerService`. If the phone has none itself, no answer would come, and every start
     * of the watch would wake the phone again; hence at most every [CredentialsRequester.MISSING_RETRY_MS] per source.
     */
    private suspend fun requestMissingPasswords(phoneNodeId: String) {
        phone.requestMissingCredentials(serverMirror.serversWithoutPassword() + audiobookshelf.withoutKey(), phoneNodeId)
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray, what: String): T? = readOrNull(what) { WearCodec.decode<T>(bytes) }

    /** Something from the phone this watch can't read stays unread; retrying wouldn't help until an update. */
    private inline fun <T> readOrNull(what: String, read: () -> T): T? =
        try {
            read()
        } catch (e: SerializationException) {
            Log.w(TAG, "$what from the phone not readable (newer app version?): ${e.message}")
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "$what from the phone not readable: ${e.message}")
            null
        }

    /** `null` for types this watch version does not know (yet) – it downloads their songs via the phone. */
    private fun WearServerDto.toMirrored(): MirroredServer? {
        val sourceType = SourceType.entries.firstOrNull { it.name == type && it.isServer }
        if (sourceType == null) {
            Log.w(TAG, "Server $sourceId: type $type is not supported")
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
        Log.d(TAG, "Manifest applied: ${manifest.selections.size} selections, ${manifest.songs.size} songs")
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
            val kind = selection.kind ?: return@forEachIndexed
            val playlistId = existing[selection.key]?.refId?.takeIf { playlistDao.getPlaylist(it) != null }
                ?.also { playlistDao.rename(it, selection.title, now) }
                ?: playlistDao.insertPlaylist(PlaylistEntity(name = selection.title, updatedAt = now))
            playlistDao.deleteEntries(playlistId)
            playlistDao.insertEntries(
                selection.songKeys.mapNotNull { songIdsByKey[it] }
                    .mapIndexed { position, songId -> PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = position) },
            )
            wearDao.upsert(WearSelectionEntity(selection.key, kind, playlistId, addedAt = index.toLong()))
        }
        val removed = existing.keys - manifest.selections.filter { it.kind != null }.mapTo(HashSet()) { it.key }
        removed.forEach { key ->
            wearDao.delete(key)
            existing.getValue(key).refId?.let { playlistDao.deletePlaylist(it) }
        }
    }

    private companion object {
        const val TAG = "ManifestApplier"
        const val PHONE_SOURCE_NAME = "Phone"
        const val MAX_SQL_VARIABLES = 900
    }
}
