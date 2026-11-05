package keinabersmart.kleinaberlaut.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRemoteLibraryTest {

    @Test
    fun `Kennung des Vorschaubilds eines Albums`() {
        assertEquals(42L, WearAlbumThumbnailFile.parse(WearAlbumThumbnailFile.uid(42)))
        assertNull(WearAlbumThumbnailFile.parse("cover:abs:item"))
        assertNull(WearAlbumThumbnailFile.parse("albumthumb:x"))
    }

    @Test
    fun `Bibliotheksindex mit Kuenstlern uebersteht gzip, alter Index ohne Kuenstler bleibt lesbar`() {
        val library = WearRemoteLibraryDto(
            albums = listOf(WearRemoteAlbumDto(1, "Mondlicht", "Nachtfalter", 4)),
            artists = listOf(WearRemoteArtistDto(7, "Nachtfalter", listOf(1))),
        )
        assertEquals(library, WearCodec.decodeCompressed<WearRemoteLibraryDto>(WearCodec.encodeCompressed(library)))
        val old = WearCodec.encodeCompressed(WearRemoteLibraryDto(albums = library.albums))
        assertEquals(emptyList<WearRemoteArtistDto>(), WearCodec.decodeCompressed<WearRemoteLibraryDto>(old).artists)
    }
}
