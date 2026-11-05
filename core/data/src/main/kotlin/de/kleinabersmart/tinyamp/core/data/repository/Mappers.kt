package de.kleinabersmart.tinyamp.core.data.repository

import de.kleinabersmart.tinyamp.core.database.model.AlbumRow
import de.kleinabersmart.tinyamp.core.database.model.ArtistRow
import de.kleinabersmart.tinyamp.core.database.model.SongRow
import de.kleinabersmart.tinyamp.core.model.Album
import de.kleinabersmart.tinyamp.core.model.Artist
import de.kleinabersmart.tinyamp.core.model.Song

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
