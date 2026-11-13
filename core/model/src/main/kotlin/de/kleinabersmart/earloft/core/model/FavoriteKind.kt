package de.kleinabersmart.earloft.core.model

/** Was sich favorisieren lässt; Server führen dafür getrennte Listen (Subsonic `star?id=`/`albumId=`/`artistId=`). */
enum class FavoriteKind { SONG, ALBUM, ARTIST }
