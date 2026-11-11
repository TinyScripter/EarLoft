package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.search.AreaSearchResults
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.SearchResults
import de.kleinabersmart.earloft.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTargetTest {

    private fun song(id: Long, title: String) = Song(id, title, "Artist", 1, "Album", 1000, 1, 1, isFavorite = false)
    private fun book(id: Long, title: String, kind: BookKind = BookKind.BOOK) =
        BookWithProgress(Book(id = id, serverId = "s", kind = kind, title = title, author = "Autor", durationMs = 1, remoteId = "r$id"), null)
    private fun feed(id: String, title: String) =
        PodcastFeed(id, "https://x/$id", title, "", "", null, autoDownload = false, lastRefreshAt = 0)

    private val music = SearchResults(
        songs = listOf(song(1, "Harry Potter Theme"), song(2, "Hedwig")),
        albums = listOf(Album(5, "Harry Potter Soundtrack", "Williams", null, 2)),
        artists = listOf(Artist(9, "John Williams", 3)),
    )

    @Test
    fun `an exact book title wins over music`() {
        val hits = AreaSearchResults(music = music, books = listOf(book(20, "Harry Potter Theme")))
        assertEquals(VoiceTarget.Spoken(20), chooseVoiceTarget("harry potter theme", hits))
    }

    @Test
    fun `an exact podcast title starts that podcast`() {
        val hits = AreaSearchResults(music = music, feeds = listOf(feed("f", "Hedwig")))
        assertEquals(VoiceTarget.Feed("f"), chooseVoiceTarget("Hedwig", hits))
    }

    @Test
    fun `an exact song title plays those songs`() {
        assertEquals(VoiceTarget.Songs(listOf(song(2, "Hedwig"))), chooseVoiceTarget("hedwig", AreaSearchResults(music = music)))
    }

    @Test
    fun `without an exact title music keeps its order of artist and album`() {
        val hits = AreaSearchResults(music = music, books = listOf(book(20, "Harry Potter und der Stein der Weisen")))
        assertEquals(VoiceTarget.Artist(9), chooseVoiceTarget("Harry", hits))
        assertEquals(VoiceTarget.Album(5), chooseVoiceTarget("Harry", hits.copy(music = music.copy(artists = emptyList()))))
    }

    @Test
    fun `with music turned off partial spoken hits are used`() {
        val hits = AreaSearchResults(
            books = listOf(book(20, "Harry Potter und der Stein der Weisen")),
            episodes = listOf(book(30, "Folge über Harry", BookKind.EPISODE)),
        )
        assertEquals(VoiceTarget.Spoken(20), chooseVoiceTarget("Harry", hits))
        assertEquals(VoiceTarget.Spoken(30), chooseVoiceTarget("Harry", hits.copy(books = emptyList())))
        assertEquals(VoiceTarget.Feed("f"), chooseVoiceTarget("Harry", hits.copy(feeds = listOf(feed("f", "Harrys Podcast")))))
    }

    @Test
    fun `nothing found plays nothing`() {
        assertEquals(VoiceTarget.None, chooseVoiceTarget("xyz", AreaSearchResults.EMPTY))
    }
}
