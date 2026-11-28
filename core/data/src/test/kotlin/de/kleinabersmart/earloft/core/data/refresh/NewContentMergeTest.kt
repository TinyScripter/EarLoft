package de.kleinabersmart.earloft.core.data.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** review5 P19: a new "new" notification of a section keeps what its unread predecessor said. */
class NewContentMergeTest {

    @Test
    fun `episodes of another podcast join the unread ones, the newer first`() {
        val unread = NewContent.Podcasts(listOf(NewEpisodes("a", "Podcast A", listOf(1))))
        val found = NewContent.Podcasts(listOf(NewEpisodes("b", "Podcast B", listOf(2))), moved = listOf("Podcast C"))

        val merged = found.withUnread(unread) as NewContent.Podcasts

        assertEquals(listOf("b" to listOf(2L), "a" to listOf(1L)), merged.episodes.map { it.feedId to it.episodeIds })
        assertEquals(listOf("Podcast C"), merged.moved)
    }

    @Test
    fun `a podcast in both counts once with all its episodes and the newer settings`() {
        val unread = NewContent.Podcasts(listOf(NewEpisodes("a", "Old title", listOf(1), autoDownload = false)), moved = listOf("C"))
        val found = NewContent.Podcasts(listOf(NewEpisodes("a", "New title", listOf(2, 1), autoDownload = true)), moved = listOf("C"))

        val merged = found.withUnread(unread) as NewContent.Podcasts

        assertEquals(listOf(NewEpisodes("a", "New title", listOf(2, 1), autoDownload = true)), merged.episodes)
        assertEquals(listOf("C"), merged.moved)
    }

    @Test
    fun `a notification left standing keeps only the newest entries`() {
        val unread = NewContent.Books((1L..MAX_MERGED).map { NewBook(it, "Old $it", "Author") })
        val found = NewContent.Books(listOf(NewBook(1_000, "New", "Author")))
        val episodes = NewContent.Podcasts(listOf(NewEpisodes("a", "A", (1L..MAX_MERGED_EPISODES).toList())))

        val merged = found.withUnread(unread) as NewContent.Books
        val mergedEpisodes = NewContent.Podcasts(listOf(NewEpisodes("a", "A", listOf(0L)))).withUnread(episodes) as NewContent.Podcasts

        assertEquals(MAX_MERGED, merged.books.size)
        assertEquals(1_000L, merged.books.first().bookId)
        assertEquals((0L until MAX_MERGED_EPISODES).toList(), mergedEpisodes.episodes.single().episodeIds)
    }

    @Test
    fun `new songs of the same album add up and the album with most new songs comes first`() {
        val unread = NewContent.Music(listOf(NewAlbum(1, "Takk...", "Sigur Rós", 2), NewAlbum(2, "Hvarf", "Sigur Rós", 3)))
        val found = NewContent.Music(listOf(NewAlbum(1, "Takk...", "Sigur Rós", 2)))

        val merged = found.withUnread(unread) as NewContent.Music

        assertEquals(listOf(1L to 4, 2L to 3), merged.albums.map { it.albumId to it.songs })
    }

    @Test
    fun `books are listed once, the newer first`() {
        val unread = NewContent.Books(listOf(NewBook(1, "Old", "A"), NewBook(2, "Both", "B")))
        val found = NewContent.Books(listOf(NewBook(3, "New", "C"), NewBook(2, "Both", "B")))

        assertEquals(listOf(3L, 2L, 1L), (found.withUnread(unread) as NewContent.Books).books.map { it.bookId })
    }

    @Test
    fun `nothing unread or another section leaves the content as it is`() {
        val found = NewContent.Books(listOf(NewBook(1, "New", "A")))

        assertSame(found, found.withUnread(null))
        assertSame(found, found.withUnread(NewContent.Music(listOf(NewAlbum(1, "X", "Y", 1)))))
    }
}
