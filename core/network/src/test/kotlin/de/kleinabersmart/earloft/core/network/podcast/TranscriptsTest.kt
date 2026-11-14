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
              {"speaker":"Anna","startTime":0.5,"endTime":0.9,"body":"Hallo"},
              {"speaker":"Anna","startTime":0.9,"endTime":1.2,"body":","},
              {"speaker":"Anna","startTime":1.2,"endTime":1.6,"body":"Welt."},
              {"speaker":"Bob","startTime":2.0,"endTime":2.5,"body":"Hi"}
            ]}
        """.trimIndent()
        val transcript = parseTranscript(json, TranscriptFormat.JSON)
        assertTrue(transcript.timed)
        assertEquals(
            listOf(
                TranscriptSegment("Hallo, Welt.", startMs = 500, endMs = 1600, speaker = "Anna"),
                TranscriptSegment("Hi", startMs = 2000, endMs = 2500, speaker = "Bob"),
            ),
            transcript.segments,
        )
    }

    @Test
    fun `a lone punctuation token does not start a paragraph`() {
        val long = "Ein Satz, der lang genug ist, um einen Absatz zu füllen, und hier mit einem Punkt endet."
        val segments = mergeSegments(
            listOf(TranscriptSegment(long, 0, 1000), TranscriptSegment("-", 1000, 1100), TranscriptSegment("Weiter", 1100, 1500)),
        )
        assertEquals(listOf("$long -", "Weiter"), segments.map { it.text })
    }

    @Test
    fun `webvtt with voices and tags`() {
        val vtt = "${Char(0xFEFF)}WEBVTT\n\nNOTE ein Kommentar\n\n1\n00:00:01.000 --> 00:00:03.500 align:start\n<v Anna>Guten <i>Morgen</i> &amp; willkommen\n\n" +
            "01:02.000 --> 01:04.000\n<v.loud Bob>Danke!</v>\n"
        val segments = parseTranscript(vtt, null).segments
        assertEquals(listOf(TranscriptSegment("Guten Morgen & willkommen", 1000, 3500, "Anna"), TranscriptSegment("Danke!", 62_000, 64_000, "Bob")), segments)
    }

    @Test
    fun `srt lines are merged until a sentence ends`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nDas ist ein ziemlich langer Satz, der über\r\n\r\n" +
            "2\r\n00:00:02,000 --> 00:00:04,000\r\nmehrere Untertitel geht und dann endlich aufhört, wie man sieht.\r\n\r\n" +
            "3\r\n00:00:04,000 --> 00:00:05,000\r\nNeuer Absatz.\r\n"
        // Als Text angekündigt, aber der Inhalt ist SRT.
        val segments = parseTranscript(srt, TranscriptFormat.TEXT).segments
        assertEquals(2, segments.size)
        assertEquals("Das ist ein ziemlich langer Satz, der über mehrere Untertitel geht und dann endlich aufhört, wie man sieht.", segments[0].text)
        assertEquals(1000L, segments[0].startMs)
        assertEquals(4000L, segments[0].endMs)
        assertEquals(TranscriptSegment("Neuer Absatz.", 4000, 5000), segments[1])
    }

    @Test
    fun `html with cite and time`() {
        val html = "<cite>Anna:</cite>\n<time>0:00</time>\n<p>Hallo &amp; willkommen.</p>\n<cite>Bob:</cite><time>1:02:03</time><p>Danke.</p>"
        val segments = parseTranscript(html, TranscriptFormat.HTML).segments
        assertEquals(listOf(TranscriptSegment("Hallo & willkommen.", 0, speaker = "Anna"), TranscriptSegment("Danke.", 3_723_000, speaker = "Bob")), segments)
    }

    @Test
    fun `plain html and text have no times`() {
        val html = parseTranscript("<div>Erster Absatz<br>Zweiter Absatz</div>", TranscriptFormat.HTML)
        assertFalse(html.timed)
        assertEquals(listOf("Erster Absatz", "Zweiter Absatz"), html.segments.map { it.text })
        val text = parseTranscript("Eins\n\nZwei\nnoch zwei", TranscriptFormat.TEXT)
        assertEquals(listOf("Eins", "Zwei noch zwei"), text.segments.map { it.text })
    }
}
