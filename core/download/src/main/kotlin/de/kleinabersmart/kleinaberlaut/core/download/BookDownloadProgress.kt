package de.kleinabersmart.kleinaberlaut.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Übertragene Bytes eines ladenden Buchs; [totalBytes] ist `null`, solange der Server die Größe nicht nennt. */
data class BookTransfer(val doneBytes: Long, val totalBytes: Long?)

/**
 * Fortschritt des gerade ladenden Buchs (nur im Speicher, der Worker meldet ihn alle halbe Sekunde), über alle seine
 * Dateien. Bei mehreren Dateien ist die Gesamtgröße eine Schätzung aus den bisher bekannten Dateien.
 */
@Singleton
class BookDownloadProgress @Inject constructor() {
    private val _transfers = MutableStateFlow<Map<Long, BookTransfer>>(emptyMap())

    /** Buch → Stand; nur für Bücher, die gerade laden. */
    val transfers: StateFlow<Map<Long, BookTransfer>> = _transfers.asStateFlow()

    /**
     * [filesDone] fertige Dateien von [filesTotal] mit zusammen [finishedBytes]; die laufende Datei hat [done] von
     * [total] Bytes.
     */
    internal fun report(bookId: Long, filesDone: Int, filesTotal: Int, finishedBytes: Long, done: Long, total: Long?) {
        val totalBytes = total?.let { (finishedBytes + it) * filesTotal / (filesDone + 1) }
        _transfers.update { it + (bookId to BookTransfer(finishedBytes + done, totalBytes)) }
    }

    internal fun clear(bookId: Long) {
        _transfers.update { it - bookId }
    }
}
