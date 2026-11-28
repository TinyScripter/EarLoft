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

/** The music part of the browse tree (concept 7.1); [BrowseTree] checks whether music is enabled. */
internal class MusicBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
) {
    /** Content of a music folder; `null` if [parentId] isn't one. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == MediaIds.RECENT -> recentAlbums(RECENT_ALBUMS)
        parentId == MediaIds.LIBRARY -> listOf(
            folderMediaItem(MediaIds.ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true),
            folderMediaItem(MediaIds.ARTISTS, context.getString(R.string.artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            folderMediaItem(MediaIds.GENRES, context.getString(R.string.genres), MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
        )
        parentId == MediaIds.ALBUMS -> libraryRepository.albums().first().map { it.toBrowsableMediaItem(context) }
        parentId == MediaIds.ARTISTS -> libraryRepository.artists().first().map { it.toBrowsableMediaItem() }
        parentId == MediaIds.GENRES -> libraryRepository.genres().first().map { it.toBrowsableMediaItem() }
        parentId == MediaIds.PLAYLISTS -> playlistRepository.playlists().first().map { it.toBrowsableMediaItem() }
        parentId == MediaIds.FAVORITES -> favoriteFolders() + favoritesRepository.favorites().first().map { it.toBrowsableSongItem(context, parentId) }
        parentId == MediaIds.FAVORITE_ALBUMS -> favoritesRepository.favoriteAlbums().first().map { it.toBrowsableMediaItem(context) }
        parentId == MediaIds.FAVORITE_ARTISTS -> favoritesRepository.favoriteArtists().first().map { it.toBrowsableMediaItem() }
        MediaIds.genreName(parentId) != null ->
            libraryRepository.albumsOfGenre(MediaIds.genreName(parentId)!!).first().map { it.toBrowsableMediaItem(context) }
        MediaIds.artistId(parentId) != null ->
            libraryRepository.artist(MediaIds.artistId(parentId)!!).first()?.albums.orEmpty().map { it.toBrowsableMediaItem(context) }
        // With the position: a playlist may hold a song twice (review5 P17).
        else -> songsOf(parentId)?.mapIndexed { position, song -> song.toBrowsableSongItem(context, parentId, position) }
    }

    /**
     * Folders of the favorite albums and artists above the favorite songs, only if there are any. A song from the favorites
     * still only enqueues the favorite songs ([songsOf]). Counted as in the folder itself, i.e. without albums and artists of
     * disabled servers: otherwise an empty folder would open (review V15).
     */
    private suspend fun favoriteFolders(): List<MediaItem> = listOfNotNull(
        folderMediaItem(MediaIds.FAVORITE_ALBUMS, context.getString(R.string.albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true)
            .takeIf { favoritesRepository.favoriteAlbums().first().isNotEmpty() },
        folderMediaItem(MediaIds.FAVORITE_ARTISTS, context.getString(R.string.artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
            .takeIf { favoritesRepository.favoriteArtists().first().isNotEmpty() },
    )

    /** Recently played albums; without listening history the most recently added. */
    suspend fun recentAlbums(limit: Int): List<MediaItem> =
        libraryRepository.recentlyPlayedAlbums(limit).first()
            .ifEmpty { libraryRepository.recentlyAddedAlbums(limit).first() }
            .map { it.toBrowsableMediaItem(context) }

    /** The songs of a folder (album, playlist, favorites, search results); `null` if [folderId] is not a song list. */
    suspend fun songsOf(folderId: String): List<Song>? = when {
        folderId == MediaIds.FAVORITES -> favoritesRepository.favorites().first()
        MediaIds.searchQuery(folderId) != null -> libraryRepository.search(MediaIds.searchQuery(folderId)!!).songs
        MediaIds.albumId(folderId) != null -> libraryRepository.album(MediaIds.albumId(folderId)!!).first()?.songs.orEmpty()
        MediaIds.playlistId(folderId) != null ->
            playlistRepository.playlist(MediaIds.playlistId(folderId)!!).first()?.entries.orEmpty().map { it.song }
        else -> null
    }

    /** All songs of an artist (their albums and their songs on others), album by album by year – as on the artist page. */
    suspend fun artistSongs(artistId: Long): List<Song> = libraryRepository.songsOfArtist(artistId)

    suspend fun albumSongs(albumId: Long): List<Song> = libraryRepository.album(albumId).first()?.songs.orEmpty()

    /** Shuffled favorites; without favorites the whole library shuffled (voice command without a term). */
    suspend fun shuffledFavorites(limit: Int): List<Song> {
        val favorites = favoritesRepository.favorites().first()
        return favorites.ifEmpty { libraryRepository.songs().first() }.shuffled().take(limit)
    }

    private companion object {
        const val RECENT_ALBUMS = 50
    }
}
