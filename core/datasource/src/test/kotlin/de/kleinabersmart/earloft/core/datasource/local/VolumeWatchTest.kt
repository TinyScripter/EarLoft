package de.kleinabersmart.earloft.core.datasource.local

import de.kleinabersmart.earloft.core.datasource.local.VolumeWatch.Companion.FORGET_AFTER_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeWatchTest {

    private val internal = "external_primary"
    private val card = "1a2b-3c4d"

    @Test
    fun `all volumes with files mounted is complete`() {
        val (complete, next) = VolumeWatch.evaluate(mapOf(internal to 1L, card to 1L), setOf(internal, card), setOf(internal, card), 10)
        assertEquals(true, complete)
        assertEquals(mapOf(internal to 10L, card to 10L), next)
    }

    @Test
    fun `an ejected card makes the scan incomplete and is remembered`() {
        val (complete, next) = VolumeWatch.evaluate(mapOf(internal to 1L, card to 1L), setOf(internal), setOf(internal), 10)
        assertEquals(false, complete)
        assertEquals("the card keeps the time it last held files", mapOf(card to 1L, internal to 10L), next)
    }

    @Test
    fun `a mounted volume without files is forgotten`() {
        val (complete, next) = VolumeWatch.evaluate(mapOf(internal to 1L, card to 1L), setOf(internal), setOf(internal, card), 10)
        assertEquals(true, complete)
        assertEquals(mapOf(internal to 10L), next)
    }

    @Test
    fun `a volume missing for a long time counts as removed`() {
        val now = 1L + FORGET_AFTER_MS
        val (complete, next) = VolumeWatch.evaluate(mapOf(internal to 1L, card to 1L), setOf(internal), setOf(internal), now)
        assertEquals(true, complete)
        assertEquals(mapOf(internal to now), next)
    }
}
