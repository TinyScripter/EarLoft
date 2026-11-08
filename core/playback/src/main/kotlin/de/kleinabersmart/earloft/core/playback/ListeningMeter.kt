package de.kleinabersmart.earloft.core.playback

/**
 * Misst gehörte **Inhaltszeit**: Zeit, in der abgespielt wird, mal Geschwindigkeit (bei 2× zählt eine Minute Wanduhr
 * zwei Minuten Inhalt, also eine Stunde Inhalt immer eine Stunde). Pausen und Springen zählen nicht, weil nur die Dauer
 * zwischen [start] und [stop] eingeht, nicht die Position. Nicht threadsicher; der Aufrufer ist der Player-Thread.
 */
internal class ListeningMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private var running = false
    private var since = 0L
    private var speed = 1f
    private var pending = 0.0

    val isRunning: Boolean get() = running

    fun start(speed: Float) {
        if (running) return
        running = true
        since = clock()
        this.speed = speed
    }

    /** Ein neues Tempo gilt ab jetzt; die Zeit davor zählt noch mit dem alten. */
    fun setSpeed(speed: Float) {
        if (running) fold()
        this.speed = speed
    }

    fun stop() {
        if (running) fold()
        running = false
    }

    /** Die seit dem letzten Aufruf gemessene Zeit in ms; die Messung läuft weiter. Der Rest unter 1 ms bleibt stehen. */
    fun drain(): Long {
        if (running) fold()
        val whole = pending.toLong()
        pending -= whole
        return whole
    }

    private fun fold() {
        val now = clock()
        pending += (now - since).coerceAtLeast(0) * speed
        since = now
    }
}
