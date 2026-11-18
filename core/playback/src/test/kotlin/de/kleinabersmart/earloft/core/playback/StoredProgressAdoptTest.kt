package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Der pausierte Player übernimmt den Stand eines anderen Geräts (Uhr ↔ Handy, Audiobookshelf). */
class StoredProgressAdoptTest {

    private fun stored(positionMs: Long, finished: Boolean = false) =
        BookProgress(positionMs, durationMs = 24_000_000, finished = finished, updatedAt = 1_000)

    @Test
    fun `a paused player jumps to a position stored by another device`() {
        assertEquals(4_600_000L, storedPositionToAdopt(stored(4_600_000), playerPositionMs = 2_553_871, playWhenReady = false))
        // Auch zurück: Der andere Stand ist schon als der neuere gespeichert worden.
        assertEquals(1_000_000L, storedPositionToAdopt(stored(1_000_000), playerPositionMs = 2_553_871, playWhenReady = false))
    }

    @Test
    fun `own saves at the position of the player change nothing`() {
        assertNull(storedPositionToAdopt(stored(2_553_871), playerPositionMs = 2_553_871, playWhenReady = false))
        assertNull(storedPositionToAdopt(stored(2_553_400), playerPositionMs = 2_553_871, playWhenReady = false))
    }

    @Test
    fun `a playing player and a finished book are left alone`() {
        assertNull(storedPositionToAdopt(stored(4_600_000), playerPositionMs = 2_553_871, playWhenReady = true))
        assertNull(storedPositionToAdopt(stored(24_000_000, finished = true), playerPositionMs = 2_553_871, playWhenReady = false))
    }
}
