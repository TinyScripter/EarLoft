package de.kleinabersmart.tinyamp.core.data.backup

import de.kleinabersmart.tinyamp.core.data.repository.pushFavoriteToServers
import de.kleinabersmart.tinyamp.core.database.dao.BackupDao
import de.kleinabersmart.tinyamp.core.database.dao.KeyedFavorite
import de.kleinabersmart.tinyamp.core.database.dao.KeyedPlayEvent
import de.kleinabersmart.tinyamp.core.database.dao.KeyedPlaylistEntry
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SyncDao
import de.kleinabersmart.tinyamp.core.database.entity.PlayEventEntity
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserSongKey
import de.kleinabersmart.tinyamp.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.tinyamp.core.database.entity.WearSelectionEntity
import de.kleinabersmart.tinyamp.core.model.WearSelectionKind
import java.util.UUID
import javax.inject.Inject

/**
 * Die Musik-Nutzerdaten der Sicherung (Review K9, Etappe 3): lokale Playlists, Favoriten von Songs ohne Server, Hörverlauf
 * und die Auswahl für die Uhr. Alles hängt an Songs und Alben, die es auf einem neuen Gerät erst nach Scan bzw. Abgleich
 * wieder gibt; sie werden über ihre Metadaten gefunden ([UserSongKey]). Aufrufe laufen in der Transaktion von
 * [UserDataBackup].
 */
class MusicBackup @Inject constructor(
    private val backupDao: BackupDao,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
) {
    /** Was davon in die Sicherung gehört; lokale Playlists ohne `backupKey` bekommen dabei einen. */
    suspend fun read(): MusicData {
        backupDao.localPlaylists().filter { it.backupKey == null }.forEach { backupDao.setBackupKey(it.playlistId, UUID.randomUUID().toString()) }
        return MusicData(
            playlists = backupDao.localPlaylists(),
            playlistEntries = backupDao.localPlaylistEntries(),
            favorites = backupDao.localFavorites(),
            playEvents = backupDao.playEvents(),
            wearSelections = wearSelections(),
        )
    }

    private suspend fun wearSelections(): List<UserWearSelectionEntity> {
        val selections = backupDao.wearSelections()
        val albums = selections.filter { it.kind == WearSelectionKind.ALBUM }.mapNotNull { it.refId }
            .chunked(MAX_ARGS).flatMap { backupDao.albumKeys(it) }.associateBy { it.albumId }
        val playlists = selections.filter { it.kind == WearSelectionKind.PLAYLIST }.mapNotNull { it.refId }
            .chunked(MAX_ARGS).flatMap { backupDao.playlists(it) }.associateBy { it.playlistId }
        return selections.mapNotNull { selection ->
            when (selection.kind) {
                WearSelectionKind.ALBUM -> albums[selection.refId]?.let { album ->
                    UserWearSelectionEntity(albumKey(album.albumArtist, album.album), selection.kind, album.albumArtist, album.album, null, selection.addedAt)
                }
                WearSelectionKind.PLAYLIST -> playlists[selection.refId]?.playlistRef()?.let { ref ->
                    UserWearSelectionEntity("playlist:$ref", selection.kind, null, null, ref, selection.addedAt)
                }
                WearSelectionKind.FAVORITES -> UserWearSelectionEntity("favorites", selection.kind, null, null, null, selection.addedAt)
            }
        }
    }

    /** Die `songId`s der Songs, die es hier gibt; fehlende Schlüssel fehlen in der Antwort. */
    suspend fun resolve(keys: Collection<UserSongKey>): Map<UserSongKey, Long> =
        keys.groupBy { it.albumArtist to it.album }.flatMap { (album, wanted) ->
            val songs = backupDao.songsOfAlbum(album.first, album.second)
            wanted.mapNotNull { key ->
                songs.firstOrNull { it.disc == key.disc && it.track == key.track && it.title == key.title }?.let { key to it.songId }
            }
        }.toMap()

    /** Ob das Ziel der Auswahl hier existiert; sonst wartet sie in der Sicherung. */
    suspend fun wearTarget(selection: UserWearSelectionEntity): Long? = when (selection.kind) {
        WearSelectionKind.ALBUM -> backupDao.albumId(selection.albumArtist.orEmpty(), selection.album.orEmpty())
        WearSelectionKind.PLAYLIST -> selection.playlistRef?.let { playlistId(it) }
        WearSelectionKind.FAVORITES -> 0
    }

    /** Legt die gesicherten Playlists leer an; ihre Einträge folgen mit [applyPlaylists]. */
    suspend fun restorePlaylists(saved: Collection<UserPlaylistEntity>) {
        saved.forEach {
            if (backupDao.playlistByBackupKey(it.playlistKey) == null) {
                playlistDao.insertPlaylist(PlaylistEntity(name = it.name, updatedAt = it.updatedAt, backupKey = it.playlistKey))
            }
        }
    }

    /**
     * Füllt die Playlists, die seit dem Zurückspielen nicht geändert wurden, mit den gesicherten Einträgen, deren Songs es
     * gibt. Liefert die Schlüssel der Playlists, denen noch Songs fehlen.
     */
    suspend fun applyPlaylists(saved: UserDataSnapshot, keys: Collection<String>): List<String> = keys.filter { key ->
        val playlist = saved.playlists[key] ?: return@filter false
        val local = backupDao.playlistByBackupKey(key) ?: return@filter false
        // Hier geändert: Diese Fassung gilt, sie wird gesichert.
        if (local.updatedAt != playlist.updatedAt || local.name != playlist.name) return@filter false
        val entries = saved.entriesOf(key)
        val songIds = resolve(entries.toSet()).let { resolved -> entries.mapNotNull { resolved[it] } }
        if (playlistDao.getSongIds(local.playlistId) != songIds) {
            playlistDao.deleteEntries(local.playlistId)
            playlistDao.insertEntries(songIds.mapIndexed { position, songId -> PlaylistEntryEntity(playlistId = local.playlistId, songId = songId, position = position) })
        }
        songIds.size < entries.size
    }

    /** Setzt Favoriten, die hier älter sind oder fehlen; liefert die, deren Song (noch) fehlt. */
    suspend fun applyFavorites(saved: Collection<UserFavoriteEntity>): List<UserFavoriteEntity> {
        val resolved = resolve(saved.map { it.song })
        val states = resolved.values.chunked(MAX_ARGS).flatMap { backupDao.favoriteStates(it) }.associateBy { it.songId }
        saved.forEach { favorite ->
            val songId = resolved[favorite.song] ?: return@forEach
            val state = states[songId] ?: return@forEach
            if (!state.isFavorite && state.favoriteChangedAt < favorite.changedAt) {
                syncDao.setSongFavorite(songId, true, favorite.changedAt)
                syncDao.pushFavoriteToServers(songId, favorite = true)
            }
        }
        return saved.filter { it.song !in resolved }
    }

    /** Ergänzt fehlende Einträge des Hörverlaufs; liefert die, deren Song (noch) fehlt. */
    suspend fun applyPlayEvents(saved: Collection<UserPlayEventEntity>): List<UserPlayEventEntity> {
        val resolved = resolve(saved.map { it.song }.toSet())
        val missing = saved.groupBy { resolved[it.song] }.flatMap { (songId, events) ->
            if (songId == null) return@flatMap emptyList()
            val known = backupDao.playedAt(songId).toSet()
            events.filter { it.playedAt !in known }.map { PlayEventEntity(songId = songId, playedAt = it.playedAt, listenedMs = it.listenedMs) }
        }
        if (missing.isNotEmpty()) backupDao.insertPlayEvents(missing)
        return saved.filter { it.song !in resolved }
    }

    /** Legt fehlende Auswahlen für die Uhr an; liefert die, deren Album bzw. Playlist (noch) fehlt. */
    suspend fun applyWearSelections(saved: Collection<UserWearSelectionEntity>): List<UserWearSelectionEntity> = saved.filter { selection ->
        val target = wearTarget(selection) ?: return@filter true
        val refId = target.takeIf { selection.kind != WearSelectionKind.FAVORITES }
        backupDao.insertWearSelection(WearSelectionEntity(selection.kind.key(refId), selection.kind, refId, selection.addedAt))
        false
    }

    private suspend fun playlistId(ref: String): Long? {
        val parts = ref.split(REF_SEPARATOR)
        return when {
            parts.size == 2 && parts[0] == LOCAL -> backupDao.playlistByBackupKey(parts[1])?.playlistId
            parts.size == 3 && parts[0] == SERVER -> backupDao.serverPlaylistId(parts[1], parts[2])
            else -> null
        }
    }

    private fun PlaylistEntity.playlistRef(): String? = when {
        boundSourceId == null -> backupKey?.let { LOCAL + REF_SEPARATOR + it }
        else -> remotePlaylistId?.let { SERVER + REF_SEPARATOR + boundSourceId + REF_SEPARATOR + it }
    }

    private companion object {
        const val MAX_ARGS = 500
        const val LOCAL = "local"
        const val SERVER = "server"
        const val REF_SEPARATOR = "\u001F"

        fun albumKey(albumArtist: String, album: String) = "album:$albumArtist$REF_SEPARATOR$album"
    }
}

/** Musik-Nutzerdaten der Hauptdatenbank, schon mit Schlüsseln (siehe [UserDataSnapshot.ofMain]). */
data class MusicData(
    val playlists: List<PlaylistEntity>,
    val playlistEntries: List<KeyedPlaylistEntry>,
    val favorites: List<KeyedFavorite>,
    val playEvents: List<KeyedPlayEvent>,
    val wearSelections: List<UserWearSelectionEntity>,
)
