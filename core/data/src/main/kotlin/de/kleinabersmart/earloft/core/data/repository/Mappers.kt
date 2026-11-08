package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.database.model.AlbumRow
import de.kleinabersmart.earloft.core.database.model.ArtistRow
import de.kleinabersmart.earloft.core.database.model.SongRow
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Song

internal fun SongRow.toSong() = Song(
    id = songId,
    title = title,
    artist = artistDisplay,
    albumId = albumId,
    albumTitle = albumTitle,
    durationMs = durationMs,
    discNumber = discNumber,
    trackNumber = trackNumber,
    isFavorite = isFavorite,
)

internal fun AlbumRow.toAlbum() = Album(
    id = albumId,
    title = title,
    artist = albumArtistDisplay,
    year = year,
    songCount = songCount,
)

internal fun ArtistRow.toArtist() = Artist(
    id = artistId,
    name = name,
    albumCount = albumCount,
)
