package de.kleinabersmart.earloft.core.data.backup

import de.kleinabersmart.earloft.core.data.repository.pushCollectionFavoriteToServers
import de.kleinabersmart.earloft.core.data.repository.pushFavoriteToServers
import de.kleinabersmart.earloft.core.data.repository.setCollectionFavorite
import de.kleinabersmart.earloft.core.database.dao.BackupDao
import de.kleinabersmart.earloft.core.database.dao.CollectionFavoriteState
import de.kleinabersmart.earloft.core.database.dao.KeyedCollectionFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedPlayEvent
import de.kleinabersmart.earloft.core.database.dao.KeyedPlaylistEntry
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.UserSongKey
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import java.util.UUID
import javax.inject.Inject

/**
 * The music user data of the backup (review K9, stage 3): local playlists, favorites of songs, albums and artists without a
 * server, listening history
 * and the selection for the watch. Everything depends on songs and albums that only exist again on a new device after scan or
 * sync; they are found via their metadata ([UserSongKey]). Calls run in the transaction of
 * [UserDataBackup].
 */
class MusicBackup @Inject constructor(
    private val backupDao: BackupDao,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
) {
    /** What of it belongs in the backup; local playlists without a `backupKey` get one in the process. */
    suspend fun read(): MusicData {
        backupDao.localPlaylists().filter { it.backupKey == null }.forEach { backupDao.setBackupKey(it.playlistId, UUID.randomUUID().toString()) }
        return MusicData(
            playlists = backupDao.localPlaylists(),
            playlistEntries = backupDao.localPlaylistEntries(),
            favorites = backupDao.localFavorites(),
            playEvents = backupDao.playEvents(),
            wearSelections = wearSelections(),
            collectionFavorites = backupDao.localCollectionFavorites(),
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

    /** The `songId`s of the songs that exist here; missing keys are missing from the result. */
    suspend fun resolve(keys: Collection<UserSongKey>): Map<UserSongKey, Long> =
        keys.groupBy { it.albumArtist to it.album }.flatMap { (album, wanted) ->
            val songs = backupDao.songsOfAlbum(album.first, album.second)
            wanted.mapNotNull { key ->
                songs.firstOrNull { it.disc == key.disc && it.track == key.track && it.title == key.title }?.let { key to it.songId }
            }
        }.toMap()

    /** Whether the selection's target exists here; otherwise it waits in the backup. */
    suspend fun wearTarget(selection: UserWearSelectionEntity): Long? = when (selection.kind) {
        WearSelectionKind.ALBUM -> backupDao.albumId(selection.albumArtist.orEmpty(), selection.album.orEmpty())
        WearSelectionKind.PLAYLIST -> selection.playlistRef?.let { playlistId(it) }
        WearSelectionKind.FAVORITES -> 0
    }

    /** Creates the backed-up playlists empty; their entries follow with [applyPlaylists]. */
    suspend fun restorePlaylists(saved: Collection<UserPlaylistEntity>) {
        saved.forEach {
            if (backupDao.playlistByBackupKey(it.playlistKey) == null) {
                playlistDao.insertPlaylist(PlaylistEntity(name = it.name, updatedAt = it.updatedAt, backupKey = it.playlistKey))
            }
        }
    }

    /** How many of the backed-up playlists [keys] don't exist here yet. */
    suspend fun missingPlaylists(keys: Collection<String>): Int = keys.count { backupDao.playlistByBackupKey(it) == null }

    /**
     * Fills the playlists that haven't been changed since restoring with the backed-up entries whose songs
     * exist. Returns the keys of the playlists that are still missing songs.
     */
    suspend fun applyPlaylists(saved: UserDataSnapshot, keys: Collection<String>): List<String> = keys.filter { key ->
        val playlist = saved.playlists[key] ?: return@filter false
        val local = backupDao.playlistByBackupKey(key) ?: return@filter false
        // Changed here: this version applies and is backed up.
        if (local.updatedAt != playlist.updatedAt || local.name != playlist.name) return@filter false
        val entries = saved.entriesOf(key)
        val songIds = resolve(entries.toSet()).let { resolved -> entries.mapNotNull { resolved[it] } }
        if (playlistDao.getSongIds(local.playlistId) != songIds) {
            playlistDao.deleteEntries(local.playlistId)
            playlistDao.insertEntries(songIds.mapIndexed { position, songId -> PlaylistEntryEntity(playlistId = local.playlistId, songId = songId, position = position) })
        }
        songIds.size < entries.size
    }

    /** Sets favorites that are older here or missing; returns those whose song is (still) missing. */
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

    /** The album or artist that exists here; otherwise the favorite waits in the backup. */
    suspend fun collectionState(favorite: UserCollectionFavoriteEntity): CollectionFavoriteState? = when (favorite.kind) {
        FavoriteKind.ALBUM -> backupDao.albumFavoriteState(favorite.artist, favorite.title)
        FavoriteKind.ARTIST -> backupDao.artistFavoriteState(favorite.artist)
        FavoriteKind.SONG -> null
    }

    /** Like [applyFavorites] for albums and artists; returns those whose album or artist is (still) missing. */
    suspend fun applyCollectionFavorites(saved: Collection<UserCollectionFavoriteEntity>): List<UserCollectionFavoriteEntity> =
        saved.filter { favorite ->
            val state = collectionState(favorite) ?: return@filter true
            if (!state.isFavorite && state.favoriteChangedAt < favorite.changedAt) {
                syncDao.setCollectionFavorite(favorite.kind, state.id, true, favorite.changedAt)
                syncDao.pushCollectionFavoriteToServers(favorite.kind, state.id, favorite = true)
            }
            false
        }

    /** Adds missing entries of the listening history; returns those whose song is (still) missing. */
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

    /** Creates missing selections for the watch; returns those whose album or playlist is (still) missing. */
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

/** Music user data of the main database, already with keys (see [UserDataSnapshot.ofMain]). */
data class MusicData(
    val playlists: List<PlaylistEntity>,
    val playlistEntries: List<KeyedPlaylistEntry>,
    val favorites: List<KeyedFavorite>,
    val playEvents: List<KeyedPlayEvent>,
    val wearSelections: List<UserWearSelectionEntity>,
    val collectionFavorites: List<KeyedCollectionFavorite>,
)
