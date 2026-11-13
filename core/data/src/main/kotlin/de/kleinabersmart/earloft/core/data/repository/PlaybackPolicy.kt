package de.kleinabersmart.earloft.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was ein Gerät beim Abspielen darf. Das Handy streamt und spielt über jede Ausgabe; die Uhr
 * (Wear-Konzept 7.3, 9) nur fertige Downloads und nicht über den eigenen Lautsprecher. Die Uhr
 * setzt die Werte beim Start der Anwendung, bevor der Wiedergabe-Service läuft.
 */
@Singleton
class PlaybackPolicy @Inject constructor() {

    /** `false`: [PlaybackSourceResolver] liefert nur lokale Dateien und fertige Downloads. */
    @Volatile
    var allowStreaming: Boolean = true

    /** `true`: Ohne passende Ausgabe (z. B. Kopfhörer) wird die Wiedergabe angehalten statt über den Lautsprecher zu laufen. */
    @Volatile
    var requireSuitableOutput: Boolean = false

    /**
     * `false`: Fortschritt und Hörsitzungen von Büchern gehen nicht selbst an Audiobookshelf. Die Uhr meldet sie dem Handy,
     * das sie weitergibt (Wear-Konzept 16.2); den Stand des Servers bringt das Handy mit.
     */
    @Volatile
    var syncsWithBookServers: Boolean = true

    /** `false`: Schütteln verlängert den Sleep-Timer nicht (Uhr: das Handgelenk bewegt sich im Schlaf zu leicht). */
    @Volatile
    var shakeToExtendSleepTimer: Boolean = true
}
