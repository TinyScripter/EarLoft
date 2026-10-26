package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.model.AlbumRow
import keinabersmart.kleinaberlaut.core.database.model.ArtistRow
import keinabersmart.kleinaberlaut.core.database.model.SongRow
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Song

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
