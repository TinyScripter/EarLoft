package de.kleinabersmart.earloft.core.network.podcast

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Lehnt XML mit eigenen Entity-Deklarationen ab, bevor der Parser es sieht. RSS und OPML brauchen keine; ein feindlicher Feed
 * könnte damit Text vervielfachen („Billion Laughs“) und bei jedem Abgleich den Speicher sprengen. Ein bloßes `<!DOCTYPE …>`
 * (alte RSS-0.91-Feeds) bleibt erlaubt, eins mit internem Teil (`[…]`, dort stehen Deklarationen) nicht. Deklarationen stehen
 * nur im Prolog, deshalb genügt der Anfang der Datei – in ihrer Kodierung gelesen (UTF-16 hat Nullbytes zwischen den Zeichen).
 * Reicht der Prolog (Kommentare, Verarbeitungsanweisungen) über den Anfang hinaus, ist das kein Feed (Review D24).
 * @return der Strom zum Weiterlesen, `null` bei eigenen Entities.
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
    val text = String(head, 0, read, charsetOf(head, read))
    return buffered.takeUnless { declaresEntities(text, whole = read < head.size) }
}

/**
 * Ob der Prolog in [text] (dem Anfang der Datei) Entities deklarieren kann: ein `<!ENTITY` oder ein `<!DOCTYPE` mit internem Teil.
 * Ohne [whole] (die Datei ist länger) gilt auch ein Prolog als verdächtig, der über [text] hinausreicht.
 */
internal fun declaresEntities(text: String, whole: Boolean): Boolean {
    if (text.contains("<!ENTITY", ignoreCase = true)) return true
    var at = 0
    while (true) {
        val open = text.indexOf('<', at)
        if (open < 0) return !whole
        val end = when {
            text.startsWith("<?", open) -> text.indexOf("?>", open + 2).let { if (it < 0) -1 else it + 2 }
            text.startsWith("<!--", open) -> text.indexOf("-->", open + 4).let { if (it < 0) -1 else it + 3 }
            text.startsWith("<!DOCTYPE", open, ignoreCase = true) -> {
                val close = text.indexOf('>', open)
                val subset = text.indexOf('[', open)
                if (subset >= 0 && (close < 0 || subset < close)) return true
                if (close < 0) -1 else close + 1
            }
            // Das Wurzelelement: Der Prolog ist zu Ende.
            else -> return false
        }
        if (end < 0) return !whole
        at = end
    }
}

/** Die Kodierung des Anfangs [head]: UTF-16/UTF-32 an Byte-Order-Mark oder Nullbytes, sonst ISO-8859-1 (für die ASCII-Zeichen des Prologs wie UTF-8). */
private fun charsetOf(head: ByteArray, length: Int): Charset {
    fun at(i: Int) = if (i < length) head[i].toInt() and 0xFF else -1
    val (b0, b1, b2, b3) = listOf(at(0), at(1), at(2), at(3))
    return when {
        b0 == 0 && b1 == 0 && (b2 == 0xFE || b2 == 0) -> Charset.forName("UTF-32BE")
        b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0 -> Charset.forName("UTF-32LE")
        b0 != 0 && b1 == 0 && b2 == 0 && b3 == 0 -> Charset.forName("UTF-32LE")
        b0 == 0xFE && b1 == 0xFF -> Charsets.UTF_16BE
        b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE
        b0 == 0 && b1 != 0 -> Charsets.UTF_16BE
        b0 != 0 && b0 != -1 && b1 == 0 -> Charsets.UTF_16LE
        else -> Charsets.ISO_8859_1
    }
}

private const val PROLOG_BYTES = 64 * 1024
