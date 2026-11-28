package de.kleinabersmart.earloft.notification

import de.kleinabersmart.earloft.core.data.refresh.NewAlbum
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewEpisodes
import de.kleinabersmart.earloft.core.data.refresh.NewShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** review5 P19: the next notification of a section reads back what the unread one said. */
@RunWith(RobolectricTestRunner::class)
class NewContentJsonTest {

    @Test
    fun `every section comes back as it was`() {
        val contents = listOf(
            NewContent.Music(listOf(NewAlbum(1, "Takk...", "Sigur Rós", 3), NewAlbum(2, "Hvarf", "", 1))),
            NewContent.Podcasts(
                listOf(NewEpisodes("a", "Podcast \"A\"", listOf(5, 4), notify = true, autoDownload = true), NewEpisodes("b", "B", listOf(9))),
                moved = listOf("C", "D"),
            ),
            NewContent.Books(listOf(NewBook(7, "Buch", "Autorin", NewShow("urn:ard:show:x", "Show")), NewBook(8, "Other", ""))),
        )

        contents.forEach { assertEquals(it, NewContentJson.decode(NewContentJson.encode(it))) }
    }

    @Test
    fun `nothing or something unknown counts as nothing unread`() {
        assertNull(NewContentJson.decode(null))
        assertNull(NewContentJson.decode("not json"))
        assertNull(NewContentJson.decode("""{"type":"videos"}"""))
        assertNull(NewContentJson.decode("""{"type":"music"}"""))
    }
}
