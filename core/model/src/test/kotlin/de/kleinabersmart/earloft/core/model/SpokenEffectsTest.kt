package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precedence episode or book > subscription > area (concept 12.4). */
class SpokenEffectsTest {

    private val area = SpokenEffects(speed = 1.2f, skipSilence = true, volumeBoostDb = 0)

    @Test
    fun `without own values the area applies`() {
        assertEquals(area, EffectLayers(area = area).effects)
    }

    @Test
    fun `the feed overrides the area value by value`() {
        val layers = EffectLayers(feed = EffectOverrides(speed = 1.5f), area = area, skipIntroS = 30, skipOutroS = 20)

        assertEquals(SpokenEffects(1.5f, skipSilence = true, volumeBoostDb = 0, skipIntroMs = 30_000, skipOutroMs = 20_000), layers.effects)
    }

    @Test
    fun `own values override the feed but intro and outro stay with the feed`() {
        val layers = EffectLayers(
            own = EffectOverrides(speed = 1f, volumeBoostDb = 6),
            feed = EffectOverrides(speed = 1.5f, skipSilence = false),
            area = area,
            skipOutroS = 20,
        )

        assertEquals(SpokenEffects(1f, skipSilence = false, volumeBoostDb = 6, skipOutroMs = 20_000), layers.effects)
        assertEquals(1.5f, layers.inherited.speed)
    }

    @Test
    fun `own values equal to the inherited ones fall away`() {
        val own = EffectOverrides(speed = 1.2f, skipSilence = false, volumeBoostDb = 0).minus(area)

        assertEquals(EffectOverrides(skipSilence = false), own)
        assertTrue(EffectOverrides(speed = 1.2001f).minus(area).isEmpty)
    }
}
