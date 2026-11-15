package de.kleinabersmart.earloft.core.download

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entscheidet in einem Worker-Lauf, ob eine unterbrochene Datei ([DownloadOutcome.Retry]) aufgibt oder wieder eingereiht wird
 * und der Lauf mit Backoff wiederholt. Das Budget von [maxAttempts] Läufen zählt WorkManager je Auftrag, nicht je Datei: Ist es
 * erschöpft, gibt deshalb nur die erste Datei des Laufs auf. Sonst schlüge bei einem länger nicht erreichbaren Server jede
 * weitere Datei sofort fehl, und die Automatik lüde diese Bücher und Folgen nie wieder.
 */
internal class RetryBudget(private val runAttemptCount: Int, private val maxAttempts: Int) {
    private val gaveUp = AtomicBoolean(false)

    /** `true`: Die Datei gibt auf. `false`: einreihen und den Lauf mit `Result.retry()` beenden. */
    fun giveUp(outcome: DownloadOutcome.Retry): Boolean {
        // Ein abgelehnter Schlüssel ändert sich durch Warten nicht.
        if (outcome.isAuthRejection) return true
        if (runAttemptCount + 1 < maxAttempts) return false
        return !gaveUp.getAndSet(true)
    }
}
