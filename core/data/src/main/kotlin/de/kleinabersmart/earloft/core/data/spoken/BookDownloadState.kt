package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.BookDownloadRow
import de.kleinabersmart.earloft.core.model.TrackDownload

/**
 * Download-Stand eines Buchs mit der Größe der fertig geladenen Dateien. [doneFiles] von [totalFiles] sind geladen; bei
 * [TrackDownload.FAILED] mit fertigen Dateien ist das Buch [partial] – die fertigen spielen vom Gerät, der Rest als Stream.
 */
data class BookDownload(val state: TrackDownload, val bytes: Long, val doneFiles: Int = 0, val totalFiles: Int = 0) {
    val partial: Boolean get() = state == TrackDownload.FAILED && doneFiles > 0
}

/**
 * Stand eines Buchs aus dem Stand seiner Dateien: „läuft“ vor „wartet“ vor „fehlgeschlagen“ – eine gescheiterte Datei hält
 * die übrigen nicht auf; fertig ist es erst, wenn alle Dateien fertig sind. Sind alle entfernt, gilt das Buch als entfernt.
 */
fun BookDownloadRow.state(): TrackDownload = when {
    running > 0 -> TrackDownload.RUNNING
    queued > 0 -> TrackDownload.QUEUED
    failed > 0 -> TrackDownload.FAILED
    total > 0 && done == total -> TrackDownload.DONE
    removed > 0 -> TrackDownload.REMOVED
    else -> TrackDownload.NONE
}

fun BookDownloadRow.toDownload(): BookDownload = BookDownload(state(), bytes, done, total)
