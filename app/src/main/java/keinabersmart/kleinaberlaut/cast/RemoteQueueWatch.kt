package keinabersmart.kleinaberlaut.cast

/**
 * Hat der Empfänger eine Warteschlange, die während der Sitzung an ihn ging, verworfen? Nach dem Senden ist die
 * Zeitachse des Players leer, bis der Empfänger meldet (bei Büchern von Audiobookshelf am Gerät bis etwa 9 s).
 * Erreicht er den Server nicht, nimmt er die Titel an, läuft sie in wenigen Sekunden ergebnislos durch und steht
 * danach ohne Titel da (am Gerät gesehen). Verworfen heißt deshalb: Erst leer, dann Titel der neuen Warteschlange,
 * dann wieder leer ([onItems]) – oder bis zum Ende der Frist nie Titel ([onTimeout]).
 *
 * Titel zählen nur, wenn sie zur neuen Warteschlange gehören und der Player dazwischen leer war; ein Neuladen
 * derselben Titel (oder die alte Warteschlange, die noch kurz zu sehen ist) löst so nichts aus. Nur Main-Thread.
 */
internal class RemoteQueueWatch {

    private var expected: Set<String>? = null
    private var wasEmpty = false
    private var sawItems = false

    val isWatching: Boolean get() = expected != null

    /** Eine Warteschlange mit diesen Media-IDs ging hinaus. */
    fun start(mediaIds: Collection<String>) {
        expected = mediaIds.toSet()
        wasEmpty = false
        sawItems = false
    }

    fun stop() {
        expected = null
    }

    /** Die Zeitachse hat sich geändert; `true`: Der Empfänger hat die Warteschlange verworfen. */
    fun onItems(mediaIds: List<String>): Boolean {
        val expected = expected ?: return false
        when {
            mediaIds.isEmpty() -> {
                if (sawItems) {
                    stop()
                    return true
                }
                wasEmpty = true
            }
            wasEmpty && mediaIds.any { it in expected } -> sawItems = true
        }
        return false
    }

    /** Ende der Frist; `true`: Es kamen nie Titel an und es sind keine da. */
    fun onTimeout(itemCount: Int): Boolean {
        if (!isWatching) return false
        val failed = !sawItems && itemCount == 0
        stop()
        return failed
    }
}
