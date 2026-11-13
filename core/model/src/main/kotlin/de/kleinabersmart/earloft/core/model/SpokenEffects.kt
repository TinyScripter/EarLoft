package de.kleinabersmart.earloft.core.model

import kotlin.math.abs

/**
 * Tempo und Wiedergabe-Effekte einer Ebene (Konzept 12.4): eines Buchs bzw. einer Folge, eines Abos oder des Bereichs.
 * `null` = von der nächsten Ebene übernehmen (Folge bzw. Buch > Abo > Bereich).
 */
data class EffectOverrides(
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    /** Lautstärke anheben in dB, 0 = aus. */
    val volumeBoostDb: Int? = null,
) {
    val isEmpty: Boolean get() = speed == null && skipSilence == null && volumeBoostDb == null

    /** Diese Werte, wo gesetzt, sonst die von [fallback]. */
    infix fun over(fallback: EffectOverrides): EffectOverrides = EffectOverrides(
        speed = speed ?: fallback.speed,
        skipSilence = skipSilence ?: fallback.skipSilence,
        volumeBoostDb = volumeBoostDb ?: fallback.volumeBoostDb,
    )

    /** Nur die Werte, die von [inherited] abweichen; gleiche fallen weg und folgen damit künftig der Ebene darunter. */
    fun minus(inherited: SpokenEffects): EffectOverrides = EffectOverrides(
        speed = speed?.takeUnless { sameSpeed(it, inherited.speed) },
        skipSilence = skipSilence?.takeUnless { it == inherited.skipSilence },
        volumeBoostDb = volumeBoostDb?.takeUnless { it == inherited.volumeBoostDb },
    )
}

/** Was beim Abspielen eines Buchs bzw. einer Folge gilt, nach der Rangfolge aufgelöst. */
data class SpokenEffects(
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val volumeBoostDb: Int = 0,
    /** Nur Folgen, je Abo: so viel am Anfang überspringen (nur beim Start bei 0) bzw. am Ende (zählt als gehört). */
    val skipIntroMs: Long = 0,
    val skipOutroMs: Long = 0,
) {
    /** Stille kürzen oder Lautstärke anheben ist an (das Tempo zählt nicht dazu). */
    val hasAudioEffects: Boolean get() = skipSilence || volumeBoostDb > 0

    companion object {
        val DEFAULT = SpokenEffects()

        /** Wahl für „Lautstärke anheben“; der `LoudnessEnhancer` begrenzt selbst, darüber übersteuert es hörbar. */
        val VOLUME_BOOST_CHOICES = listOf(0, 3, 6, 9)

        /** Wahl für das Standardtempo in den Einstellungen; im Player geht es feiner (Schritt 0,05). */
        val SPEED_CHOICES = listOf(0.75f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        /** Wahl für Intro und Outro überspringen, in Sekunden (0 = aus). */
        val SKIP_CHOICES_S = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180)
    }
}

/**
 * Die Ebenen eines Buchs bzw. einer Folge: eigene Werte, die des Abos (nur Folgen) und die Vorgabe des Bereichs, dazu
 * Intro und Outro des Abos.
 */
data class EffectLayers(
    val own: EffectOverrides = EffectOverrides(),
    /** `null` bei Büchern und Folgen ohne Abo. */
    val feed: EffectOverrides? = null,
    val area: SpokenEffects = SpokenEffects.DEFAULT,
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
) {
    /** Was ohne eigene Werte gälte (Abo, sonst Bereich). */
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

/** Die Vorgabe als Ebene, bei der jeder Wert gesetzt ist. */
fun SpokenEffects.overrides(): EffectOverrides = EffectOverrides(speed, skipSilence, volumeBoostDb)

/** Tempi gelten als gleich, wenn sie sich um weniger als einen halben Schritt (0,05) unterscheiden. */
fun sameSpeed(a: Float, b: Float): Boolean = abs(a - b) < 0.01f
