package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Der Musik-Teil des Browse-Trees (Konzept 7.1); ob Musik eingeschaltet ist, prüft [BrowseTree]. */
internal class MusicBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
) {
    /** Inhalt eines Musik-Ordners; `null`, wenn [parentId] keiner ist. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == MediaIds.RECENT -> recentAlbums(RECENT_ALBUMS)
        parentId == MediaIds.LIBRARY -> listOf(
            folderMediaItem(MediaIds.ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true),
            folderMediaItem(MediaIds.ARTISTS, context.getString(R.string.artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        )
        parentId == MediaIds.ALBUMS -> libraryRepository.albums().first().map { it.toBrowsableMediaItem(context) }
        parentId == MediaIds.ARTISTS -> libraryRepository.artists().first().map { it.toBrowsableMediaItem() }
        parentId == MediaIds.PLAYLISTS -> playlistRepository.playlists().first().map { it.toBrowsableMediaItem() }
        MediaIds.artistId(parentId) != null ->
            libraryRepository.artist(MediaIds.artistId(parentId)!!).first()?.albums.orEmpty().map { it.toBrowsableMediaItem(context) }
        else -> songsOf(parentId)?.map { it.toBrowsableSongItem(context, parentId) }
    }

    /** Zuletzt gehörte Alben; ohne Hörverlauf die zuletzt hinzugefügten. */
    suspend fun recentAlbums(limit: Int): List<MediaItem> =
        libraryRepository.recentlyPlayedAlbums(limit).first()
            .ifEmpty { libraryRepository.recentlyAddedAlbums(limit).first() }
            .map { it.toBrowsableMediaItem(context) }

    /** Die Songs eines Ordners (Album, Playlist, Favoriten, Suchtreffer); `null`, wenn [folderId] keine Songliste ist. */
    suspend fun songsOf(folderId: String): List<Song>? = when {
        folderId == MediaIds.FAVORITES -> favoritesRepository.favorites().first()
        MediaIds.searchQuery(folderId) != null -> libraryRepository.search(MediaIds.searchQuery(folderId)!!).songs
        MediaIds.albumId(folderId) != null -> libraryRepository.album(MediaIds.albumId(folderId)!!).first()?.songs.orEmpty()
        MediaIds.playlistId(folderId) != null ->
            playlistRepository.playlist(MediaIds.playlistId(folderId)!!).first()?.entries.orEmpty().map { it.song }
        else -> null
    }

    /** Alle Songs der Alben eines Künstlers, Album für Album. */
    suspend fun artistSongs(artistId: Long): List<Song> {
        val albums = libraryRepository.artist(artistId).first()?.albums.orEmpty()
        return albums.flatMap { libraryRepository.album(it.id).first()?.songs.orEmpty() }
    }

    suspend fun albumSongs(albumId: Long): List<Song> = libraryRepository.album(albumId).first()?.songs.orEmpty()

    /** Gemischte Favoriten, ohne Favoriten die ganze Bibliothek gemischt (Sprachbefehl ohne Begriff). */
    suspend fun shuffledFavorites(limit: Int): List<Song> {
        val favorites = favoritesRepository.favorites().first()
        return favorites.ifEmpty { libraryRepository.songs().first() }.shuffled().take(limit)
    }

    private companion object {
        const val RECENT_ALBUMS = 50
    }
}
