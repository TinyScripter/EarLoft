package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRemoteLibraryTest {

    @Test
    fun `thumbnail ids survive arbitrary characters`() {
        val refs = listOf(
            WearThumbnailFile.Ref.Album(42),
            WearThumbnailFile.Ref.Feed("local:My Podcast"),
            WearThumbnailFile.Ref.Book("https://feed.example/x", "guid:12/a b?ä"),
        )
        for (ref in refs) {
            val uid = WearThumbnailFile.uid(ref)
            assertEquals(ref, WearThumbnailFile.parse(uid))
            // No slash in the channel's path.
            assertEquals(false, uid.contains('/'))
        }
        assertNull(WearThumbnailFile.parse("cover:abs:item"))
        assertNull(WearThumbnailFile.parse("thumb:album:x"))
    }

    @Test
    fun `library index with artists survives gzip, old index without artists stays readable`() {
        val library = WearRemoteLibraryDto(
            albums = listOf(WearRemoteAlbumDto(1, "Moonlight", "Night Moth", 4)),
            artists = listOf(WearRemoteArtistDto(7, "Night Moth", listOf(1))),
            podcasts = listOf(WearRemotePodcastDto("f1", "Podcast")),
            books = listOf(WearRemoteBookDto(3, "Book", "Author", "abs", "item", started = true)),
            favoriteAlbumIds = listOf(1),
            favoriteArtistIds = listOf(7),
        )
        assertEquals(library, WearCodec.decodeCompressed<WearRemoteLibraryDto>(WearCodec.encodeCompressed(library)))
        val old = WearCodec.encodeCompressed(WearRemoteLibraryDto(albums = library.albums))
        assertEquals(emptyList<WearRemoteArtistDto>(), WearCodec.decodeCompressed<WearRemoteLibraryDto>(old).artists)
        assertEquals(emptyList<Long>(), WearCodec.decodeCompressed<WearRemoteLibraryDto>(old).favoriteAlbumIds)
    }
}
