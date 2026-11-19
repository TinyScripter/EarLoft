package de.kleinabersmart.earloft.core.model

import kotlin.math.abs

/**
 * Speed and playback effects of one layer (concept 12.4): of a book or episode, a subscription or the section.
 * `null` = inherit from the next layer (episode or book > subscription > section).
 */
data class EffectOverrides(
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    /** Volume boost in dB, 0 = off. */
    val volumeBoostDb: Int? = null,
) {
    val isEmpty: Boolean get() = speed == null && skipSilence == null && volumeBoostDb == null

    /** These values where set, otherwise those of [fallback]. */
    infix fun over(fallback: EffectOverrides): EffectOverrides = EffectOverrides(
        speed = speed ?: fallback.speed,
        skipSilence = skipSilence ?: fallback.skipSilence,
        volumeBoostDb = volumeBoostDb ?: fallback.volumeBoostDb,
    )

    /** Only the values that differ from [inherited]; equal ones are dropped and thus follow the layer below from now on. */
    fun minus(inherited: SpokenEffects): EffectOverrides = EffectOverrides(
        speed = speed?.takeUnless { sameSpeed(it, inherited.speed) },
        skipSilence = skipSilence?.takeUnless { it == inherited.skipSilence },
        volumeBoostDb = volumeBoostDb?.takeUnless { it == inherited.volumeBoostDb },
    )
}

/** What applies when playing a book or episode, resolved by precedence. */
data class SpokenEffects(
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val volumeBoostDb: Int = 0,
    /** Episodes only, per subscription: skip this much at the start (only when starting at 0) or at the end (counts as played). */
    val skipIntroMs: Long = 0,
    val skipOutroMs: Long = 0,
) {
    /** Silence trimming or volume boost is on (speed doesn't count). */
    val hasAudioEffects: Boolean get() = skipSilence || volumeBoostDb > 0

    companion object {
        val DEFAULT = SpokenEffects()

        /** Choices for "Volume boost"; the `LoudnessEnhancer` limits itself, above this it audibly distorts. */
        val VOLUME_BOOST_CHOICES = listOf(0, 3, 6, 9)

        /** Choices for the default speed in the settings; the player allows finer steps (0.05). */
        val SPEED_CHOICES = listOf(0.75f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        /** Choices for skipping intro and outro, in seconds (0 = off). */
        val SKIP_CHOICES_S = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180)
    }
}

/**
 * The layers of a book or episode: own values, those of the subscription (episodes only) and the section's default, plus
 * the subscription's intro and outro.
 */
data class EffectLayers(
    val own: EffectOverrides = EffectOverrides(),
    /** `null` for books and episodes without a subscription. */
    val feed: EffectOverrides? = null,
    val area: SpokenEffects = SpokenEffects.DEFAULT,
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
) {
    /** What would apply without own values (subscription, otherwise section). */
    val inherited: SpokenEffects
        get() {
            val merged = (feed ?: EffectOverrides()) over area.overrides()
            return SpokenEffects(
                speed = merged.speed ?: 1f,
                skipSilence = merged.skipSilence ?: false,
                volumeBoostDb = merged.volumeBoostDb ?: 0,
                skipIntroMs = skipIntroS * 1000L,
                skipOutroMs = skipOutroS * 1000L,
            )
        }

    val effects: SpokenEffects
        get() = inherited.let { base ->
            base.copy(
                speed = own.speed ?: base.speed,
                skipSilence = own.skipSilence ?: base.skipSilence,
                volumeBoostDb = own.volumeBoostDb ?: base.volumeBoostDb,
            )
        }
}

/** The default as a layer in which every value is set. */
fun SpokenEffects.overrides(): EffectOverrides = EffectOverrides(speed, skipSilence, volumeBoostDb)

/** Speeds count as equal if they differ by less than half a step (0.05). */
fun sameSpeed(a: Float, b: Float): Boolean = abs(a - b) < 0.01f
