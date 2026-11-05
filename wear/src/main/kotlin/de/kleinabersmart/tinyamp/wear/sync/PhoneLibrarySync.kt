package de.kleinabersmart.tinyamp.wear.sync

import android.util.Log
import androidx.room.withTransaction
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import de.kleinabersmart.tinyamp.core.data.podcast.EpisodeQueue
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.tinyamp.core.data.spoken.MirroredAbsServer
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySyncer
import de.kleinabersmart.tinyamp.core.data.wear.MirroredServer
import de.kleinabersmart.tinyamp.core.data.wear.WearServerMirror
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.dao.WearDao
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.database.entity.WearSelectionEntity
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.wearsync.WearCodec
import de.kleinabersmart.tinyamp.core.wearsync.WearCredentialsRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearManifest
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.WearPodcastManifest
import de.kleinabersmart.tinyamp.core.wearsync.WearServersDto
import de.kleinabersmart.tinyamp.core.wearsync.WearSettingsDto
import de.kleinabersmart.tinyamp.core.wearsync.assetPayloadsAt
import de.kleinabersmart.tinyamp.core.wearsync.hashesAt
import de.kleinabersmart.tinyamp.core.wearsync.payloadsAt
import de.kleinabersmart.tinyamp.core.wearsync.sendPayload
import de.kleinabersmart.tinyamp.wear.download.WatchState
import de.kleinabersmart.tinyamp.wear.download.WearDownloadPlanner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Übernimmt den Stand des Handys in die Datenbank der Uhr (Wear-Konzept 6/7): erst die Server (auch die von Audiobookshelf,
 * 16.2), dann das Manifest, dann Folgen und Bücher (16.1/16.2). Liest immer den aktuellen Stand der DataItems – mehrfaches
 * Ausführen schadet nicht.
 */
@Singleton
class PhoneLibrarySync @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val serverMirror: WearServerMirror,
    private val audiobookshelf: AudiobookshelfServers,
    private val manifestApplier: ManifestApplier,
    private val podcastMirror: PodcastMirror,
    private val episodeQueue: EpisodeQueue,
    private val state: WatchState,
    private val planner: WearDownloadPlanner,
) {
    private val mutex = Mutex()

    suspend fun sync() = mutex.withLock {
        // Zuerst die Einstellungen: Qualität und Speichergrenze bestimmen, was der Planer einreiht.
        val settingsChanged = dataClient.payloadsAt(WearPaths.SETTINGS).values.firstOrNull()
            ?.let { state.updateSettings(WearCodec.decode<WearSettingsDto>(it)) } == true
        val servers = dataClient.payloadsAt(WearPaths.SERVERS).entries.firstOrNull()
        // Jeder Prozessstart der Uhr stößt einen Lauf an (Nachricht vom Handy, Download-Versuch, Kachel …). Ist seit der
        // letzten Übernahme nichts Neues gekommen, bleibt es beim Nachfragen nach Passwörtern und beim Planen.
        val podcastsHash = dataClient.hashesAt(WearPaths.PODCASTS).values.firstOrNull()
        val stamp = "${servers?.value?.let(WearCodec::hash)}:${dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()}:$podcastsHash"
        if (!settingsChanged && stamp == state.appliedPhoneStamp) {
            servers?.let { requestMissingPasswords(phoneNodeId = it.key) }
            planner.plan()
            return@withLock
        }
        if (servers != null) {
            val dto = WearCodec.decode<WearServersDto>(servers.value)
            serverMirror.apply(dto.servers.map { MirroredServer(it.sourceId, it.name, it.baseUrl, it.username, it.priority, it.formPost) })
            audiobookshelf.mirror(dto.spokenServers.map { MirroredAbsServer(it.serverId, it.name, it.baseUrl) })
            requestMissingPasswords(phoneNodeId = servers.key)
        }
        dataClient.assetPayloadsAt(WearPaths.MANIFEST).values.firstOrNull()
            ?.let { manifestApplier.apply(WearCodec.decodeManifest(it)) }
        dataClient.assetPayloadsAt(WearPaths.PODCASTS).values.firstOrNull()
            ?.let { episodeQueue.setOrder(podcastMirror.apply(WearCodec.decodeCompressed<WearPodcastManifest>(it))) }
        planner.plan(replaceSchedule = settingsChanged)
        state.appliedPhoneStamp = stamp
    }

    /**
     * Fragt beim Handy nach Passwörtern und API-Schlüsseln, die der Uhr fehlen (neuer Server, Uhr zurückgesetzt). Die
     * Antwort kommt als Message an den `WatchListenerService`. Ist das Handy nicht erreichbar, fragt der nächste Lauf.
     */
    private suspend fun requestMissingPasswords(phoneNodeId: String) {
        (serverMirror.serversWithoutPassword() + audiobookshelf.withoutKey()).forEach { sourceId ->
            Log.d(TAG, "Passwort für $sourceId anfordern")
            messageClient.sendPayload(phoneNodeId, WearPaths.CREDENTIALS_REQUEST, WearCodec.encode(WearCredentialsRequest(sourceId)))
        }
    }

    private companion object {
        const val TAG = "PhoneLibrarySync"
    }
}

/**
 * Schreibt ein Manifest in die Datenbank: Songs über den `LibrarySyncer` (je Quelle eine
 * [WearManifestSource]), Auswahlen als Geräte-Playlists (Wear-Konzept 7.2). In `wear_selections` zeigt
 * `refId` auf der Uhr auf diese Playlist; `addedAt` ist die Position im Manifest (Reihenfolge des Handys).
 */
class ManifestApplier @Inject constructor(
    private val database: TinyAmpDatabase,
    private val sourceDao: SourceDao,
    private val wearDao: WearDao,
    private val playlistDao: PlaylistDao,
    private val librarySyncer: LibrarySyncer,
) {
    suspend fun apply(manifest: WearManifest) {
        sourceDao.insertIfAbsent(SourceEntity(WearManifestSource.PHONE_SOURCE_ID, SourceType.PHONE, PHONE_SOURCE_NAME))
        val servers = sourceDao.getByType(SourceType.SUBSONIC).mapTo(HashSet()) { it.sourceId }
        val tracks = manifestTracks(manifest, servers)
        // Auch Quellen ohne Tracks: Ihr Abgleich löscht, was aus allen Auswahlen gefallen ist.
        servers.forEach { librarySyncer.sync(WearManifestSource(it, SourceType.SUBSONIC, tracks[it].orEmpty())) }
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
