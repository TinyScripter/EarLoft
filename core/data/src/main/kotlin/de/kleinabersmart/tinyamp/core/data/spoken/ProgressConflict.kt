package de.kleinabersmart.tinyamp.core.data.spoken

import de.kleinabersmart.tinyamp.core.model.BookProgress
import kotlin.math.abs

/** Was beim Abgleich des Fortschritts mit dem Server geschieht (Konzept 12.3). */
internal enum class ProgressDecision {
    /** Der Server hat nichts Neueres: lokal bleibt. Ist es noch ungemeldet, geht es beim nächsten Senden hoch. */
    KeepLocal,

    /** Der Server hat den neueren Stand. */
    TakeRemote,
}

internal object ProgressConflict {

    /** Positionen, die weniger auseinanderliegen, sind für den Nutzer „dieselbe“ – dann gibt es keinen Hinweis. */
    const val NOTICE_THRESHOLD_MS = 5_000L

    /** Die neuere Änderung gewinnt; bei Gleichstand bleibt lokal. */
    fun resolve(local: BookProgress?, remote: BookProgress): ProgressDecision = when {
        local == null -> ProgressDecision.TakeRemote
        remote.updatedAt > local.updatedAt -> ProgressDecision.TakeRemote
        else -> ProgressDecision.KeepLocal
    }

    /** Springt die Wiedergabe durch die Übernahme spürbar? */
    fun isNoticeable(local: BookProgress?, remote: BookProgress): Boolean =
        local != null && abs(local.positionMs - remote.positionMs) > NOTICE_THRESHOLD_MS
}
