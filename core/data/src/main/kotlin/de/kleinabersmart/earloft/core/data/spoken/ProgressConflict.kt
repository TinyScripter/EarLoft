package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookProgress
import kotlin.math.abs

/** What happens when reconciling progress with the server (concept 12.3). */
internal enum class ProgressDecision {
    /** The server has nothing newer: local stays. If it's still unreported, it goes up with the next send. */
    KeepLocal,

    /** The server has the newer state. */
    TakeRemote,
}

internal object ProgressConflict {

    /** Positions closer together than this are "the same" for the user – then there's no notice. */
    const val NOTICE_THRESHOLD_MS = 5_000L

    /** The newer change wins; on a tie local stays. */
    fun resolve(local: BookProgress?, remote: BookProgress): ProgressDecision = when {
        local == null -> ProgressDecision.TakeRemote
        remote.updatedAt > local.updatedAt -> ProgressDecision.TakeRemote
        else -> ProgressDecision.KeepLocal
    }

    /** Does playback jump noticeably due to the adoption? */
    fun isNoticeable(local: BookProgress?, remote: BookProgress): Boolean =
        local != null && abs(local.positionMs - remote.positionMs) > NOTICE_THRESHOLD_MS
}
