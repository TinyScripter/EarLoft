package de.kleinabersmart.earloft.core.data.repository

import android.net.Uri

/**
 * Liefert Dateien des Handys an einen Cast-Empfänger aus (Konzept 7.5, Chromecast Etappe 2): ein kleiner HTTP-Server im
 * WLAN, der nur während einer Cast-Sitzung läuft. Die Adressen enthalten ein Geheimnis der Sitzung – nicht speichern, nicht
 * loggen. Gebunden in der App.
 */
interface PhoneCastServer {

    /** Adresse für [uri] (`content://` oder `file://`); `null`, wenn das Handy gerade keine Adresse im WLAN hat. */
    fun fileUrl(uri: Uri, mimeType: String): String?

    /** Adresse für das Cover eines Albums, geladen erst beim Abruf; `null` wie bei [fileUrl]. */
    fun albumCoverUrl(albumId: Long): String?

    /** Adresse für das Cover eines Buchs bzw. einer Folge; `null` wie bei [fileUrl]. */
    fun bookCoverUrl(bookId: Long): String?

    companion object {
        /** [CastMedia.sourceId] für Dateien vom Handy; wie ein Server, den der Empfänger nicht erreicht, ausschließbar. */
        const val SOURCE_ID = "phone"
    }
}
