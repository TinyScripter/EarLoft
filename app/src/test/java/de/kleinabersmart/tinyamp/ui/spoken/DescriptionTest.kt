package de.kleinabersmart.tinyamp.ui.spoken

import android.app.Application
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
}
