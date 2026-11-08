package de.kleinabersmart.earloft.core.network.podcast

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Lehnt XML mit eigenen Entity-Deklarationen (`<!ENTITY` im Prolog) ab, bevor der Parser es sieht. RSS und OPML brauchen
 * keine; ein feindlicher Feed könnte damit Text vervielfachen („Billion Laughs“) und bei jedem Abgleich den Speicher
 * sprengen. Ein bloßes `<!DOCTYPE …>` (alte RSS-0.91-Feeds) bleibt erlaubt. Deklarationen stehen nur im Prolog, deshalb
 * genügt der Anfang der Datei. @return der Strom zum Weiterlesen, `null` bei eigenen Entities.
 */
internal fun InputStream.rejectingEntityDeclarations(): InputStream? {
    val buffered = if (this is BufferedInputStream) this else BufferedInputStream(this, PROLOG_BYTES)
    buffered.mark(PROLOG_BYTES)
    val head = ByteArray(PROLOG_BYTES)
    var read = 0
    while (read < head.size) {
        val n = buffered.read(head, read, head.size - read)
        if (n < 0) break
        read += n
    }
    buffered.reset()
    return buffered.takeUnless { String(head, 0, read, Charsets.ISO_8859_1).contains("<!ENTITY", ignoreCase = true) }
}

private const val PROLOG_BYTES = 64 * 1024
