package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptsTest {

    @Test
    fun `formats by type and extension`() {
        assertEquals(TranscriptFormat.JSON, transcriptFormat("application/json", "https://e.com/t"))
        assertEquals(TranscriptFormat.VTT, transcriptFormat("text/vtt; charset=utf-8", "https://e.com/t"))
        assertEquals(TranscriptFormat.SRT, transcriptFormat("application/x-subrip", "https://e.com/t"))
        assertEquals(TranscriptFormat.SRT, transcriptFormat(null, "https://e.com/t.SRT?x=1"))
        assertNull(transcriptFormat("application/pdf", "https://e.com/t.pdf"))
    }

    @Test
    fun `picks the most useful transcript`() {
        val picked = pickTranscript(
            listOf("https://e.com/t.txt" to "text/plain", "https://e.com/t.json" to "application/json", "https://e.com/t.vtt" to "text/vtt"),
        )
        assertEquals("https://e.com/t.json" to "application/json", picked)
        assertNull(pickTranscript(listOf("https://e.com/t.pdf" to "application/pdf")))
    }

    @Test
    fun `json words become paragraphs with speakers`() {
        val json = """
            {"version":"1.0.0","segments":[
              {"speaker":"Anna","startTime":0.5,"endTime":0.9,"body":"Hello"},
              {"speaker":"Anna","startTime":0.9,"endTime":1.2,"body":","},
              {"speaker":"Anna","startTime":1.2,"endTime":1.6,"body":"world."},
              {"speaker":"Bob","startTime":2.0,"endTime":2.5,"body":"Hi"}
            ]}
        """.trimIndent()
        val transcript = parseTranscript(json, TranscriptFormat.JSON)
        assertTrue(transcript.timed)
        assertEquals(
            listOf(
                TranscriptSegment("Hello, world.", startMs = 500, endMs = 1600, speaker = "Anna"),
                TranscriptSegment("Hi", startMs = 2000, endMs = 2500, speaker = "Bob"),
            ),
            transcript.segments,
        )
    }

    @Test
    fun `a lone punctuation token does not start a paragraph`() {
        val long = "A sentence that is long enough to fill a paragraph, and that ends here with a full stop."
        val segments = mergeSegments(
            listOf(TranscriptSegment(long, 0, 1000), TranscriptSegment("-", 1000, 1100), TranscriptSegment("Next", 1100, 1500)),
        )
        assertEquals(listOf("$long -", "Next"), segments.map { it.text })
    }

    @Test
    fun `webvtt with voices and tags`() {
        val vtt = "${Char(0xFEFF)}WEBVTT\n\nNOTE a comment\n\n1\n00:00:01.000 --> 00:00:03.500 align:start\n<v Anna>Good <i>morning</i> &amp; welcome\n\n" +
            "01:02.000 --> 01:04.000\n<v.loud Bob>Thanks!</v>\n"
        val segments = parseTranscript(vtt, null).segments
        assertEquals(listOf(TranscriptSegment("Good morning & welcome", 1000, 3500, "Anna"), TranscriptSegment("Thanks!", 62_000, 64_000, "Bob")), segments)
    }

    @Test
    fun `srt lines are merged until a sentence ends`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nThis is a fairly long sentence that runs\r\n\r\n" +
            "2\r\n00:00:02,000 --> 00:00:04,000\r\nacross several subtitles and then finally stops, as you can see.\r\n\r\n" +
            "3\r\n00:00:04,000 --> 00:00:05,000\r\nNew paragraph.\r\n"
        // Announced as text, but the content is SRT.
        val segments = parseTranscript(srt, TranscriptFormat.TEXT).segments
        assertEquals(2, segments.size)
        assertEquals("This is a fairly long sentence that runs across several subtitles and then finally stops, as you can see.", segments[0].text)
        assertEquals(1000L, segments[0].startMs)
        assertEquals(4000L, segments[0].endMs)
        assertEquals(TranscriptSegment("New paragraph.", 4000, 5000), segments[1])
    }

    @Test
    fun `html with cite and time`() {
        val html = "<cite>Anna:</cite>\n<time>0:00</time>\n<p>Hello &amp; welcome.</p>\n<cite>Bob:</cite><time>1:02:03</time><p>Thanks.</p>"
        val segments = parseTranscript(html, TranscriptFormat.HTML).segments
        assertEquals(listOf(TranscriptSegment("Hello & welcome.", 0, speaker = "Anna"), TranscriptSegment("Thanks.", 3_723_000, speaker = "Bob")), segments)
    }

    @Test
    fun `many paragraphs without closing tag are read in linear time`() {
        // review5 P37: valid HTML; each unclosed <p> made the search run to the end of the text.
        val html = "<p>Some words here. ".repeat(20_000) + "<cite>Anna:</cite><time>0:01</time><p>The end.</p>"
        val started = System.nanoTime()

        val segments = parseTranscript(html, TranscriptFormat.HTML).segments

        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
        assertEquals(TranscriptSegment("The end.", 1_000, speaker = "Anna"), segments.last())
        assertEquals(20_000, segments.dropLast(1).sumOf { it.text.split("Some words here.").size - 1 })
    }

    @Test
    fun `a paragraph without closing tag keeps its text and ends where the next one begins`() {
        val html = "<p>First.<p>Second.<cite>Bob:</cite><time>0:05</time><p>Third.</p>"

        val segments = parseTranscript(html, TranscriptFormat.HTML).segments

        assertEquals(
            listOf(TranscriptSegment("First."), TranscriptSegment("Second."), TranscriptSegment("Third.", 5_000, speaker = "Bob")),
            segments,
        )
    }

    @Test
    fun `plain html and text have no times`() {
        val html = parseTranscript("<div>First paragraph<br>Second paragraph</div>", TranscriptFormat.HTML)
        assertFalse(html.timed)
        assertEquals(listOf("First paragraph", "Second paragraph"), html.segments.map { it.text })
        val text = parseTranscript("One\n\nTwo\nstill two", TranscriptFormat.TEXT)
        assertEquals(listOf("One", "Two still two"), text.segments.map { it.text })
    }
}
