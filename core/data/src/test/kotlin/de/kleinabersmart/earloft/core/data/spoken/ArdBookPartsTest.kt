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
        val known = listOf(known(0, "1", "Part 1"), known(1, "2", "Part 2"))

        val merged = mergeArdParts(known, listOf(offered("1-new", "Part 1", 1_001_000), offered("2", "Part 2")), NOW)

        assertEquals(listOf("https://a.example/1-new.mp3", "https://a.example/2.mp3"), merged.map { it.track.remoteFileId })
        // The length stays, otherwise the positions after it would shift.
        assertEquals(listOf(0L, 1_000_000L), merged.map { it.track.startMs })
        assertEquals(listOf(null, null), merged.map { it.track.unavailableSince })
    }

    @Test
    fun `a part with the same title id stays the same part under a new address, name and length`() {
        val known = listOf(
            known(0, "1", "Part 1", itemId = "a"),
            known(1, "2", "Part 2", state = TrackDownload.DONE, itemId = "b"),
        )
        // ARD Sounds re-cut and renamed both; name and length no longer match.
        val offered = listOf(
            ArdPart("a", "The beginning", "https://b.example/1.mp3", "audio/mpeg", 1_300_000, availableUntil = null),
            ArdPart("b", "The end", "https://b.example/2.mp3", "audio/mpeg", 1_300_000, availableUntil = null),
        )

        val merged = mergeArdParts(known, offered, NOW)

        assertEquals(2, merged.size)
        // The non-downloaded one takes the new address, the downloaded one keeps its own; lengths and names stay.
        assertEquals(listOf("https://b.example/1.mp3", "https://a.example/2.mp3"), merged.map { it.track.remoteFileId })
        assertEquals(listOf(1_000_000L, 1_000_000L), merged.map { it.track.durationMs })
        assertEquals(listOf("Part 1", "Part 2"), merged.map { it.title })
        assertEquals(listOf(null, null), merged.map { it.track.unavailableSince })
    }

    @Test
    fun `the title id comes before the address`() {
        // Two parts swap their addresses (ARD Sounds served them swapped and corrected it).
        val known = listOf(known(0, "1", "Part 1", itemId = "a"), known(1, "2", "Part 2", itemId = "b"))
        val offered = listOf(
            ArdPart("a", "Part 1", "https://a.example/2.mp3", "audio/mpeg", 1_000_000, availableUntil = 7),
            ArdPart("b", "Part 2", "https://a.example/1.mp3", "audio/mpeg", 1_000_000, availableUntil = 9),
        )

        val merged = mergeArdParts(known, offered, NOW)

        assertEquals(listOf("a", "b"), merged.map { it.track.remoteItemId })
        assertEquals(listOf("https://a.example/2.mp3", "https://a.example/1.mp3"), merged.map { it.track.remoteFileId })
        assertEquals(listOf(7L, 9L), merged.map { it.until })
    }

    @Test
    fun `a part without a title id is still found by its address, and an id nobody offers any more is gone`() {
        val known = listOf(known(0, "1", "Part 1"), known(1, "2", "Part 2", itemId = "old"))

        val merged = mergeArdParts(known, listOf(offered("1", "Part 1"), offered("3", "Part 3")), NOW)

        assertEquals(listOf("1", "old", "3"), merged.map { it.track.remoteItemId })
        assertEquals(listOf(null, NOW, null), merged.map { it.track.unavailableSince })
    }

    @Test
    fun `a downloaded part keeps its file address and a gone part keeps the time it went`() {
        val known = listOf(
            known(0, "1", "Part 1", state = TrackDownload.DONE),
            known(1, "2", "Part 2", unavailableSince = 5),
            known(2, "3", "Part 3"),
        )

        val merged = mergeArdParts(known, listOf(offered("1-new", "Part 1"), offered("4", "Part 4")), NOW)

        assertEquals(
            listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3", "https://a.example/4.mp3"),
            merged.map { it.track.remoteFileId },
        )
        assertEquals(listOf(null, 5L, NOW, null), merged.map { it.track.unavailableSince })
        // The title's identifier comes from ARD Sounds, also for the downloaded part.
        assertEquals(listOf("1-new", null, null, "4"), merged.map { it.track.remoteItemId })
        assertEquals(listOf(0, 1, 2, 3), merged.map { it.track.trackIndex })
        assertEquals(listOf("Part 1", "Part 2", "Part 3", "Part 4"), merged.map { it.title })
    }

    @Test
    fun `the book is available until the next unloaded part expires`() {
        val parts = listOf(
            ArdBookPart(track(0, state = TrackDownload.DONE), "Part 1", until = 100),
            ArdBookPart(track(1), "Part 2", until = 300),
            ArdBookPart(track(2), "Part 3", until = 200),
        )

        assertEquals(200L, ardAvailableUntil(parts, whenAllLoaded = 100))
        // Everything available downloaded: as ARD Sounds names it.
        assertEquals(100L, ardAvailableUntil(parts.take(1), whenAllLoaded = 100))
        // An unlimited part that isn't downloaded yet: no expiry, even if another one has disappeared.
        assertNull(ardAvailableUntil(listOf(ArdBookPart(track(0, unavailableSince = 50), "Part 1", 50), ArdBookPart(track(1), "Part 2", null)), null))
        // Only disappeared parts not downloaded: everything available is there.
        assertEquals(100L, ardAvailableUntil(listOf(ArdBookPart(track(0, unavailableSince = 50), "Part 1", 50), ArdBookPart(track(1, state = TrackDownload.DONE), "Part 2", 300)), 100))
    }

    @Test
    fun `saved parts come back in their order, with the gone ones`() {
        val saved = encodeArdParts(
            listOf(
                KeyedBookPart("k", 1, "https://a.example/2.mp3", 2_000, null, null, "Part 2", remoteItemId = "urn:ard:publication:2"),
                KeyedBookPart("k", 0, "https://a.example/1.mp3", 1_000, "audio/mpeg", 5, "Part\t1\n(abridged)"),
            ),
        )

        val parts = decodeArdParts(saved)

        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3"), parts.map { it.track.remoteFileId })
        assertEquals(listOf(0L, 1_000L), parts.map { it.track.startMs })
        assertEquals(listOf(1_000L, 2_000L), parts.map { it.track.durationMs })
        assertEquals(listOf("audio/mpeg", null), parts.map { it.track.mimeType })
        assertEquals(listOf(5L, null), parts.map { it.track.unavailableSince })
        assertEquals(listOf("Part 1 (abridged)", "Part 2"), parts.map { it.title })
        assertEquals(listOf(null, "urn:ard:publication:2"), parts.map { it.track.remoteItemId })
        // An older backup without title identifiers.
        assertNull(decodeArdParts("https://a.example/1.mp3\t1000\t\t\tPart 1").single().track.remoteItemId)
        // Without a backup of the parts or unreadable: the parts as ARD Sounds offers them.
        assertTrue(decodeArdParts(null).isEmpty())
        assertTrue(decodeArdParts("https://a.example/1.mp3\tshort").isEmpty())
    }

    @Test
    fun `chapters in a part that is gone and not downloaded are unavailable`() {
        val tracks = listOf(
            track(0, unavailableSince = 5),
            track(1, state = TrackDownload.DONE, unavailableSince = 5),
            track(2),
        )
        val chapters = (0..2).map { BookChapterEntity(1, it, "Part ${it + 1}", it * 1_000_000L, (it + 1) * 1_000_000L) }

        assertEquals(listOf(true, false, false), chaptersOf(chapters, tracks).map { it.unavailable })
    }

    @Test
    fun `positions on the server move up by the parts that are missing there`() {
        val missing = listOf(track(0, unavailableSince = 5), track(2, unavailableSince = 5))

        // In part 2: shifted forward by part 1.
        assertEquals(500_000L, positionWithout(1_500_000, missing))
        // In a missing part: the start of the next one.
        assertEquals(0L, positionWithout(300_000, missing))
        assertEquals(1_000_000L, positionWithout(2_400_000, missing))
        // After both: shifted forward by both.
        assertEquals(1_200_000L, positionWithout(3_200_000, missing))
    }

    private fun known(
        index: Int,
        id: String,
        title: String,
        state: TrackDownload = TrackDownload.NONE,
        unavailableSince: Long? = null,
        itemId: String? = null,
    ) = ArdBookPart(track(index, "https://a.example/$id.mp3", state, unavailableSince).copy(remoteItemId = itemId), title, unavailableSince)

    private fun track(index: Int, url: String = "https://a.example/$index.mp3", state: TrackDownload = TrackDownload.NONE, unavailableSince: Long? = null) =
        BookTrackEntity(1, index, url, index * 1_000_000L, 1_000_000, "audio/mpeg", state, unavailableSince = unavailableSince)

    private fun offered(id: String, title: String, durationMs: Long = 1_000_000) =
        ArdPart(id, title, "https://a.example/$id.mp3", "audio/mpeg", durationMs, availableUntil = null)

    private companion object {
        const val NOW = 1_000L
    }
}
