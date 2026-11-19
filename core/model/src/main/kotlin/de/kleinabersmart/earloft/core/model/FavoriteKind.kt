package de.kleinabersmart.earloft.core.model

/** What can be favorited; servers keep separate lists for this (Subsonic `star?id=`/`albumId=`/`artistId=`). */
enum class FavoriteKind { SONG, ALBUM, ARTIST }
