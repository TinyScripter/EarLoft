package de.kleinabersmart.kleinaberlaut.core.data.spoken

import de.kleinabersmart.kleinaberlaut.core.database.dao.BookDownloadRow
import de.kleinabersmart.kleinaberlaut.core.model.TrackDownload

/** Download-Stand eines Buchs mit der Größe der fertig geladenen Dateien. */
data class BookDownload(val state: TrackDownload, val bytes: Long)

/**
 * Stand eines Buchs aus dem Stand seiner Dateien: ein Fehler macht das ganze Buch unbrauchbar, sonst zählt „läuft“
 * vor „wartet“; fertig ist es erst, wenn alle Dateien fertig sind. Sind alle entfernt, gilt das Buch als entfernt.
 */
fun BookDownloadRow.state(): TrackDownload = when {
    failed > 0 -> TrackDownload.FAILED
    running > 0 -> TrackDownload.RUNNING
    queued > 0 -> TrackDownload.QUEUED
    total > 0 && done == total -> TrackDownload.DONE
    removed > 0 -> TrackDownload.REMOVED
    else -> TrackDownload.NONE
}
