package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.KeyedBookPart
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdBookPartsTest {

    @Test
    fun `a republished part under a new address takes the place of the old one`() {
        val known = listOf(known(0, "1", "Teil 1"), known(1, "2", "Teil 2"))

        val merged = mergeArdParts(known, listOf(offered("1-neu", "Teil 1", 1_001_000), offered("2", "Teil 2")), NOW)

        assertEquals(listOf("https://a.example/1-neu.mp3", "https://a.example/2.mp3"), merged.map { it.track.remoteFileId })
        // Die Länge bleibt, sonst verschöben sich die Positionen dahinter.
        assertEquals(listOf(0L, 1_000_000L), merged.map { it.track.startMs })
        assertEquals(listOf(null, null), merged.map { it.track.unavailableSince })
    }

    @Test
    fun `a downloaded part keeps its file address and a gone part keeps the time it went`() {
        val known = listOf(
            known(0, "1", "Teil 1", state = TrackDownload.DONE),
            known(1, "2", "Teil 2", unavailableSince = 5),
            known(2, "3", "Teil 3"),
        )

        val merged = mergeArdParts(known, listOf(offered("1-neu", "Teil 1"), offered("4", "Teil 4")), NOW)

        assertEquals(
            listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3", "https://a.example/4.mp3"),
            merged.map { it.track.remoteFileId },
        )
        assertEquals(listOf(null, 5L, NOW, null), merged.map { it.track.unavailableSince })
        assertEquals(listOf(0, 1, 2, 3), merged.map { it.track.trackIndex })
        assertEquals(listOf("Teil 1", "Teil 2", "Teil 3", "Teil 4"), merged.map { it.title })
    }

    @Test
    fun `the book is available until the next unloaded part expires`() {
        val parts = listOf(
            ArdBookPart(track(0, state = TrackDownload.DONE), "Teil 1", until = 100),
            ArdBookPart(track(1), "Teil 2", until = 300),
            ArdBookPart(track(2), "Teil 3", until = 200),
        )

        assertEquals(200L, ardAvailableUntil(parts, whenAllLoaded = 100))
        // Alles Abrufbare geladen: wie ARD Sounds es nennt.
        assertEquals(100L, ardAvailableUntil(parts.take(1), whenAllLoaded = 100))
        // Ein unbefristeter Teil, der noch nicht geladen ist: kein Ablauf, auch wenn ein anderer weggefallen ist.
        assertNull(ardAvailableUntil(listOf(ArdBookPart(track(0, unavailableSince = 50), "Teil 1", 50), ArdBookPart(track(1), "Teil 2", null)), null))
        // Nur noch weggefallene Teile ungeladen: Alles Abrufbare ist da.
        assertEquals(100L, ardAvailableUntil(listOf(ArdBookPart(track(0, unavailableSince = 50), "Teil 1", 50), ArdBookPart(track(1, state = TrackDownload.DONE), "Teil 2", 300)), 100))
    }

    @Test
    fun `saved parts come back in their order, with the gone ones`() {
        val saved = encodeArdParts(
            listOf(
                KeyedBookPart("k", 1, "https://a.example/2.mp3", 2_000, null, null, "Teil 2"),
                KeyedBookPart("k", 0, "https://a.example/1.mp3", 1_000, "audio/mpeg", 5, "Teil\t1\n(gekürzt)"),
            ),
        )

        val parts = decodeArdParts(saved)

        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3"), parts.map { it.track.remoteFileId })
        assertEquals(listOf(0L, 1_000L), parts.map { it.track.startMs })
        assertEquals(listOf(1_000L, 2_000L), parts.map { it.track.durationMs })
        assertEquals(listOf("audio/mpeg", null), parts.map { it.track.mimeType })
        assertEquals(listOf(5L, null), parts.map { it.track.unavailableSince })
        assertEquals(listOf("Teil 1 (gekürzt)", "Teil 2"), parts.map { it.title })
        // Ohne Sicherung der Teile oder unlesbar: die Teile, wie ARD Sounds sie anbietet.
        assertTrue(decodeArdParts(null).isEmpty())
        assertTrue(decodeArdParts("https://a.example/1.mp3\tkurz").isEmpty())
    }

    @Test
    fun `chapters in a part that is gone and not downloaded are unavailable`() {
        val tracks = listOf(
            track(0, unavailableSince = 5),
            track(1, state = TrackDownload.DONE, unavailableSince = 5),
            track(2),
        )
        val chapters = (0..2).map { BookChapterEntity(1, it, "Teil ${it + 1}", it * 1_000_000L, (it + 1) * 1_000_000L) }

        assertEquals(listOf(true, false, false), chaptersOf(chapters, tracks).map { it.unavailable })
    }

    @Test
    fun `positions on the server move up by the parts that are missing there`() {
        val missing = listOf(track(0, unavailableSince = 5), track(2, unavailableSince = 5))

        // In Teil 2: um Teil 1 vor.
        assertEquals(500_000L, positionWithout(1_500_000, missing))
        // In einem fehlenden Teil: der Anfang des nächsten.
        assertEquals(0L, positionWithout(300_000, missing))
        assertEquals(1_000_000L, positionWithout(2_400_000, missing))
        // Hinter beiden: um beide vor.
        assertEquals(1_200_000L, positionWithout(3_200_000, missing))
    }

    private fun known(index: Int, id: String, title: String, state: TrackDownload = TrackDownload.NONE, unavailableSince: Long? = null) =
        ArdBookPart(track(index, "https://a.example/$id.mp3", state, unavailableSince), title, unavailableSince)

    private fun track(index: Int, url: String = "https://a.example/$index.mp3", state: TrackDownload = TrackDownload.NONE, unavailableSince: Long? = null) =
        BookTrackEntity(1, index, url, index * 1_000_000L, 1_000_000, "audio/mpeg", state, unavailableSince = unavailableSince)

    private fun offered(id: String, title: String, durationMs: Long = 1_000_000) =
        ArdPart(id, title, "https://a.example/$id.mp3", "audio/mpeg", durationMs, availableUntil = null)

    private companion object {
        const val NOW = 1_000L
    }
}
