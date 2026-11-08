package de.kleinabersmart.tinyamp.core.data.spoken

/** Sprungweiten der Spulen-Knöpfe bei Büchern bzw. Folgen (Konzept 12.4), in Sekunden. */
data class SeekSteps(val backSeconds: Int, val forwardSeconds: Int) {
    val backMs: Long get() = backSeconds * 1000L
    val forwardMs: Long get() = forwardSeconds * 1000L

    companion object {
        /** Wahlmöglichkeiten in Sekunden, für beide Richtungen gleich. */
        val CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

        const val DEFAULT_BACK_S = 10
        const val DEFAULT_FORWARD_S = 30

        val DEFAULT = SeekSteps(DEFAULT_BACK_S, DEFAULT_FORWARD_S)
    }
}
