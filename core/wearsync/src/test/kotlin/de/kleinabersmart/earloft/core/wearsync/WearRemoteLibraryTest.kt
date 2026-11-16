package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRemoteLibraryTest {

    @Test
    fun `Kennungen der Vorschaubilder ueberstehen beliebige Zeichen`() {
        val refs = listOf(
            WearThumbnailFile.Ref.Album(42),
            WearThumbnailFile.Ref.Feed("local:Mein Podcast"),
            WearThumbnailFile.Ref.Book("https://feed.example/x", "guid:12/a b?ä"),
        )
        for (ref in refs) {
            val uid = WearThumbnailFile.uid(ref)
            assertEquals(ref, WearThumbnailFile.parse(uid))
            // Kein Schrägstrich im Pfad des Kanals.
            assertEquals(false, uid.contains('/'))
        }
        assertNull(WearThumbnailFile.parse("cover:abs:item"))
        assertNull(WearThumbnailFile.parse("thumb:album:x"))
    }

    @Test
    fun `Bibliotheksindex mit Kuenstlern uebersteht gzip, alter Index ohne Kuenstler bleibt lesbar`() {
        val library = WearRemoteLibraryDto(
            albums = listOf(WearRemoteAlbumDto(1, "Mondlicht", "Nachtfalter", 4)),
            artists = listOf(WearRemoteArtistDto(7, "Nachtfalter", listOf(1))),
            podcasts = listOf(WearRemotePodcastDto("f1", "Podcast")),
            books = listOf(WearRemoteBookDto(3, "Buch", "Autor", "abs", "item", started = true)),
            favoriteAlbumIds = listOf(1),
            favoriteArtistIds = listOf(7),
        )
        assertEquals(library, WearCodec.decodeCompressed<WearRemoteLibraryDto>(WearCodec.encodeCompressed(library)))
        val old = WearCodec.encodeCompressed(WearRemoteLibraryDto(albums = library.albums))
        assertEquals(emptyList<WearRemoteArtistDto>(), WearCodec.decodeCompressed<WearRemoteLibraryDto>(old).artists)
        assertEquals(emptyList<Long>(), WearCodec.decodeCompressed<WearRemoteLibraryDto>(old).favoriteAlbumIds)
    }
}
