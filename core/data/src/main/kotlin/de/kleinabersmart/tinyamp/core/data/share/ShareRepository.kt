package de.kleinabersmart.tinyamp.core.data.share

import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import javax.inject.Inject
import javax.inject.Singleton

/** Was sich teilen lässt (Konzept 13); Playlists bewusst nicht. */
sealed interface ShareTarget {
    data class Song(val songId: Long) : ShareTarget
    data class Album(val albumId: Long) : ShareTarget
    data class Artist(val artistId: Long) : ShareTarget
}

/** Baut Titel und Link fürs Teilen allein aus der Datenbank; `null`, wenn es den Eintrag nicht mehr gibt. */
@Singleton
class ShareRepository @Inject constructor(
    private val libraryDao: LibraryDao,
) {
    suspend fun content(target: ShareTarget): ShareContent? = when (target) {
        is ShareTarget.Song -> libraryDao.getSongEntity(target.songId)?.let { song ->
            val title = titleWithArtist(song.title, song.artistDisplay)
            content(title, ShareLinks.musicBrainz(MusicBrainzKind.RECORDING, song.mbid, song.title, song.artistDisplay))
        }
        is ShareTarget.Album -> libraryDao.getAlbumLookup(target.albumId)?.let { album ->
            val title = titleWithArtist(album.title, album.artist)
            content(title, ShareLinks.musicBrainz(MusicBrainzKind.RELEASE, album.mbid, album.title, album.artist))
        }
        is ShareTarget.Artist -> libraryDao.getArtist(target.artistId)?.let { artist ->
            content(artist.name, ShareLinks.musicBrainz(MusicBrainzKind.ARTIST, artist.mbid, artist.name))
        }
    }

    private fun titleWithArtist(title: String, artist: String?) =
        if (artist.isNullOrBlank()) title else "$title – $artist"

    private fun content(title: String, vararg lines: String?) =
        ShareContent(subject = title, text = (listOf(title) + lines.filterNotNull()).joinToString("\n"))
}
