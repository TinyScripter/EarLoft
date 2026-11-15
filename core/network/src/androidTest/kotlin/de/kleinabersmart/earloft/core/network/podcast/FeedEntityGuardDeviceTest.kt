package de.kleinabersmart.earloft.core.network.podcast

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Eigene XML-Entities mit dem Parser von Android statt kxml2 der JVM-Tests (Review D24). Ohne Schutz expandiert er sie nicht,
 * sondern bricht ab (geprüft 2026-11-15, Android 16); [PodcastFeedParser] lehnt solche Feeds trotzdem vorher ab, in UTF-8,
 * UTF-16 und hinter einem langen Prolog: falls eine andere Android-Version oder ein anderer Parser doch expandiert.
 */
@RunWith(AndroidJUnit4::class)
class FeedEntityGuardDeviceTest {

    private val laughs = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>""" +
        """<rss version="2.0"><channel><title>&lol2;</title></channel></rss>"""

    @Test
    fun androidParserDoesNotExpandOwnEntities() {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(laughs.byteInputStream(), null)
        try {
            while (!(parser.next() == XmlPullParser.START_TAG && parser.name == "title")) Unit
            fail("${parser.javaClass.name} expandiert eigene Entities: ${parser.nextText()}")
        } catch (_: XmlPullParserException) {
        }
    }

    @Test
    fun feedsWithOwnEntitiesAreRefused() {
        val utf16 = laughs.replace("<?xml version=\"1.0\"?>", "<?xml version=\"1.0\" encoding=\"UTF-16\"?>")
        val feeds = mapOf(
            "UTF-8" to laughs.toByteArray(),
            "UTF-16 BE mit BOM" to "﻿$utf16".toByteArray(Charsets.UTF_16BE),
            "UTF-16 LE ohne BOM" to utf16.toByteArray(Charsets.UTF_16LE),
            "langer Prolog" to laughs.replace("?>", "?><!--${"x".repeat(70_000)}-->").toByteArray(),
        )
        for ((label, bytes) in feeds) {
            try {
                PodcastFeedParser.parse(bytes.inputStream())
                fail("$label: PodcastFeedException erwartet")
            } catch (_: PodcastFeedException) {
            }
        }
    }

    @Test
    fun plainUtf16FeedStaysReadable() {
        val fine = """<?xml version="1.0" encoding="UTF-16"?><!-- Feed --><!DOCTYPE rss><rss version="2.0"><channel><title>Gut</title></channel></rss>"""
        assertEquals("Gut", PodcastFeedParser.parse("﻿$fine".toByteArray(Charsets.UTF_16BE).inputStream()).title)
    }
}
