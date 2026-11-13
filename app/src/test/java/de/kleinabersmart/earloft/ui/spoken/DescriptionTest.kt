package de.kleinabersmart.earloft.ui.spoken

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `fromHtml` braucht Android (`Html`), deshalb Robolectric – ohne die App-Klasse (WorkManager, Hilt). */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DescriptionTest {

    @Test
    fun `an image in front leaves no placeholder`() {
        val raw = "<p><img src=\"https://example.org/cover.jpg\" alt=\"\"/><br/>Der Erfolg bekommt Risse.</p>"
        assertEquals("Der Erfolg bekommt Risse.", formatDescription(raw).text)
    }

    @Test
    fun `images in the middle are dropped, the text stays`() {
        val raw = "<p>Vorher</p><IMG SRC='a.png'><p>Nachher &amp; mehr</p>"
        val text = formatDescription(raw).text
        assertEquals(false, '￼' in text)
        assertEquals(true, text.startsWith("Vorher") && text.endsWith("Nachher & mehr"))
    }

    @Test
    fun `plain text keeps its line breaks`() {
        assertEquals("Zeile 1\nZeile 2", formatDescription("  Zeile 1\nZeile 2 ").text)
    }

    @Test
    fun `bare addresses become links without trailing punctuation`() {
        val text = formatDescription("Mehr unter https://example.org/folge-1. Oder (www.example.org/a_(b)), danke")
        assertEquals(
            listOf("https://example.org/folge-1", "https://www.example.org/a_(b)"),
            text.urls(),
        )
    }

    @Test
    fun `bare addresses after html are linked, html links stay once`() {
        val raw = "<p>Siehe <a href=\"https://a.example/x\">https://a.example/x</a> und https://b.example/y</p>"
        assertEquals(listOf("https://a.example/x", "https://b.example/y"), formatDescription(raw).urls())
    }

    @Test
    fun `timestamps jump only with a listener and inside the episode`() {
        val raw = "00:00 Intro\n12:34 Thema\n1:02:03 Schluss\n2:10:00 zu spät\nUm 17:00 Uhr live"
        assertEquals(emptyList<String>(), formatDescription(raw).tags())
        val jumps = mutableListOf<Long>()
        val text = formatDescription(raw, durationMs = 2 * 3_600_000L, onTimestamp = { jumps += it })
        assertEquals(listOf("t:0", "t:754000", "t:3723000"), text.tags())
        text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()
            .forEach { it.linkInteractionListener?.onClick(it) }
        assertEquals(listOf(0L, 754_000L, 3_723_000L), jumps)
    }

    @Test
    fun `numbers in addresses are no timestamps`() {
        val text = formatDescription("http://example.org:8080/12:34 und 3:45", onTimestamp = {})
        assertEquals(listOf("t:225000"), text.tags())
        assertEquals(listOf("http://example.org:8080/12:34"), text.urls())
    }

    private fun AnnotatedString.urls() =
        getLinkAnnotations(0, length).map { it.item }.filterIsInstance<LinkAnnotation.Url>().map { it.url }

    private fun AnnotatedString.tags() =
        getLinkAnnotations(0, length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>().map { it.tag }
}
