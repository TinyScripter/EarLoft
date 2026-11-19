package de.kleinabersmart.earloft.core.data.spoken

/** Skip intervals of the seek buttons for books or episodes (concept 12.4), in seconds. */
data class SeekSteps(val backSeconds: Int, val forwardSeconds: Int) {
    val backMs: Long get() = backSeconds * 1000L
    val forwardMs: Long get() = forwardSeconds * 1000L

    companion object {
        /** Options in seconds, the same for both directions. */
        val CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

        const val DEFAULT_BACK_S = 10
        const val DEFAULT_FORWARD_S = 30

        val DEFAULT = SeekSteps(DEFAULT_BACK_S, DEFAULT_FORWARD_S)
    }
}

/**
 * What the notification, the system media controls and Android Auto show for books instead of ⏮/⏭ (`books_improvements.md`
 * M4): the seek buttons ([SEEK], default) or previous/next chapter ([CHAPTERS]; books without chapters seek instead). In the compact
 * view Android shows only three buttons, hence the choice instead of both. Episodes always seek.
 */
enum class BookButtons { SEEK, CHAPTERS }
