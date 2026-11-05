package de.kleinabersmart.kleinaberlaut.core.playback

/**
 * Beim Wechsel aufs Cast-Gerät übergibt der Player die Warteschlange, aber der Empfänger meldet sie erst nach dem Laden
 * zurück (am Gerät gesehen: gut eine Sekunde). Bis dahin ist die Warteschlange leer, und die Wiedergabe-Ansicht zeigte
 * „Nichts läuft“. So lange bleibt hier der letzte Stand vom Handy stehen, schon mit [PlayerState.isRemote]; die Position
 * läuft vom letzten bekannten Wert weiter. Lädt der Empfänger nichts, beendet der Cast-Player die Sitzung, und mit dem
 * Wechsel zurück aufs Handy endet auch das Festhalten.
 */
internal class RemoteHandOver {

    private var holding = false
    private var positionMs = 0L
    private var positionAtMs = 0L

    /** 0, solange nichts läuft. */
    private var positionSpeed = 0f

    /**
     * [next] ist der Stand des Players, [queueEmpty] ob seine Warteschlange leer ist, [positionMs] seine Position zum
     * Zeitpunkt [nowMs]. Liefert den Stand, der angezeigt werden soll.
     */
    fun update(previous: PlayerState, next: PlayerState, queueEmpty: Boolean, positionMs: Long, nowMs: Long): PlayerState {
        holding = next.isRemote && queueEmpty && previous.hasMedia && (holding || !previous.isRemote)
        if (holding) return previous.copy(isRemote = true)
        this.positionMs = positionMs
        positionAtMs = nowMs
        positionSpeed = if (next.isPlaying) next.speed else 0f
        return next
    }

    /** Die fortgeschriebene Position, solange festgehalten wird; sonst `null`. */
    fun positionMs(nowMs: Long): Long? =
        if (holding) positionMs + ((nowMs - positionAtMs) * positionSpeed).toLong() else null
}
