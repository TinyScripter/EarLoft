package de.kleinabersmart.tinyamp.wear.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.android.gms.wearable.DataClient
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
import de.kleinabersmart.tinyamp.core.wearsync.WearManifest
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.WearPodcastManifest
import de.kleinabersmart.tinyamp.core.wearsync.WearServerDto
import de.kleinabersmart.tinyamp.core.wearsync.WearServersDto
import de.kleinabersmart.tinyamp.core.wearsync.WearSettingsDto
import de.kleinabersmart.tinyamp.core.wearsync.assetPayloadsAt
import de.kleinabersmart.tinyamp.core.wearsync.hashesAt
import de.kleinabersmart.tinyamp.core.wearsync.payloadsAt
import de.kleinabersmart.tinyamp.wear.download.WatchState
import de.kleinabersmart.tinyamp.wear.download.WearDownloadPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
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
) {
    private val mutex = Mutex()

    /**
     * Ändert sich mit jedem Update der Uhr-App: Eine neue Version versteht vielleicht mehr vom selben Stand (z. B. Server
     * einer neuen Art in `otherServers`), den die alte schon als übernommen vermerkt hatte.
     */
    private val appVersionStamp: Long by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }

    suspend fun sync() = mutex.withLock {
        // Zuerst die Einstellungen: Qualität und Speichergrenze bestimmen, was der Planer einreiht. Versteht diese Uhr sie
        // nicht (neuerer Wert vom Handy), gelten die bisherigen – Server und Manifest kommen trotzdem.
        val settingsChanged = dataClient.payloadsAt(WearPaths.SETTINGS).values.firstOrNull()
            ?.let { bytes -> decodeOrNull<WearSettingsDto>(bytes, "Einstellungen") }
            ?.let { state.updateSettings(it) } == true
        val servers = dataClient.payloadsAt(WearPaths.SERVERS).entries.firstOrNull()
        // Jeder Prozessstart der Uhr stößt einen Lauf an (Nachricht vom Handy, Download-Versuch, Kachel …). Ist seit der
        // letzten Übernahme nichts Neues gekommen, bleibt es beim Nachfragen nach Passwörtern und beim Planen.
        val podcastsHash = dataClient.hashesAt(WearPaths.PODCASTS).values.firstOrNull()
        val manifestHash = dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()
        val stamp = "$appVersionStamp:${servers?.value?.let(WearCodec::hash)}:$manifestHash:$podcastsHash"
        if (!settingsChanged && stamp == state.appliedPhoneStamp) {
            servers?.let { requestMissingPasswords(phoneNodeId = it.key) }
            planner.plan()
            return@withLock
        }
        if (servers != null) {
            val dto = WearCodec.decode<WearServersDto>(servers.value)
            serverMirror.apply((dto.servers + dto.otherServers).mapNotNull { it.toMirrored() })
            audiobookshelf.mirror(dto.spokenServers.map { MirroredAbsServer(it.serverId, it.name, it.baseUrl) })
            requestMissingPasswords(phoneNodeId = servers.key)
        }
        val manifest = dataClient.assetPayloadsAt(WearPaths.MANIFEST).values.firstOrNull()
        manifest?.let { manifestApplier.apply(WearCodec.decodeManifest(it)) }
        val podcasts = dataClient.assetPayloadsAt(WearPaths.PODCASTS).values.firstOrNull()
        if (podcasts != null) {
            // Erst die Stellen melden: Was das neue Manifest nicht mehr enthält, löscht der Spiegel samt Fortschritt.
            episodeReporter.report()
            episodeQueue.setOrder(podcastMirror.apply(WearCodec.decodeCompressed<WearPodcastManifest>(podcasts)))
        }
        planner.plan(replaceSchedule = settingsChanged)
        // Ein DataItem, dessen Asset (noch) nicht lesbar war (Handy außer Reichweite, Übertragung läuft), gilt nicht als
        // übernommen; sonst käme es erst mit der nächsten Änderung am Handy.
        if ((manifestHash == null || manifest != null) && (podcastsHash == null || podcasts != null)) {
            state.appliedPhoneStamp = stamp
        }
    }

    /**
     * Fragt beim Handy nach Passwörtern und API-Schlüsseln, die der Uhr fehlen (neuer Server, Uhr zurückgesetzt). Die
     * Antwort kommt als Message an den `WatchListenerService`. Ist das Handy nicht erreichbar, fragt der nächste Lauf.
     */
    private suspend fun requestMissingPasswords(phoneNodeId: String) {
        credentials.request(serverMirror.serversWithoutPassword() + audiobookshelf.withoutKey(), phoneNodeId)
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray, what: String): T? =
        try {
            WearCodec.decode<T>(bytes)
        } catch (e: SerializationException) {
            Log.w(TAG, "$what des Handys nicht lesbar (neuere App-Version?): ${e.message}")
            null
        }

    /** `null` für Typen, die diese Uhr-Version (noch) nicht kennt – ihre Songs lädt sie über das Handy. */
    private fun WearServerDto.toMirrored(): MirroredServer? {
        val sourceType = SourceType.entries.firstOrNull { it.name == type && it.isServer }
        if (sourceType == null) {
            Log.w(TAG, "Server $sourceId: Typ $type wird nicht unterstützt")
            return null
        }
        return MirroredServer(sourceId, sourceType, name, baseUrl, username, priority, formPost)
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
        val serverSources = sourceDao.getServers()
        val servers = serverSources.mapTo(HashSet()) { it.sourceId }
        val keepPhone = wearDao.doneDownloadRemoteIds(WearManifestSource.PHONE_SOURCE_ID).toSet()
        val tracks = manifestTracks(manifest, servers, keepPhone)
        // Auch Quellen ohne Tracks: Ihr Abgleich löscht, was aus allen Auswahlen gefallen ist.
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
