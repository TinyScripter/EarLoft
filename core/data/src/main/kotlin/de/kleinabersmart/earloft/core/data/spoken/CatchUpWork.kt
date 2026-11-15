package de.kleinabersmart.earloft.core.data.spoken

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker

/**
 * Gemeinsame Regeln der Nachhol-Worker ([BookProgressWorker], [AbsSessionWorker]): Sie melden, was offline nicht an
 * Audiobookshelf ging, und wiederholen mit wachsendem Abstand (30 s bis 5 h).
 */
internal object CatchUpWork {

    /**
     * Läufe, nach denen aufgegeben wird (zusammen gut 8 h). Was dann noch aussteht, bleibt `dirty` und geht mit dem
     * nächsten Abgleich bzw. Abspielen hinaus; sonst wiederholte ein Server, den es nicht mehr gibt oder dessen Anmeldung
     * abgelaufen ist, für immer (Review D1).
     */
    const val MAX_ATTEMPTS = 10

    fun result(done: Boolean, runAttemptCount: Int): ListenableWorker.Result =
        if (done || runAttemptCount + 1 >= MAX_ATTEMPTS) ListenableWorker.Result.success() else ListenableWorker.Result.retry()

    /**
     * Ein laufender oder wartender Auftrag bleibt: Scheitert ein Senden im Worker selbst, plant er sich darüber neu ein;
     * mit `REPLACE` bräche das den eigenen Lauf ab und reihte sofort einen neuen ein, ohne Backoff (Review D1). [replace]
     * nur für Nutzeraktionen, die gleich hinausgehen sollen.
     */
    fun policy(replace: Boolean): ExistingWorkPolicy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
}
