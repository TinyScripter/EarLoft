package de.kleinabersmart.earloft.core.datasource.local

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

/** Eine Datei neben den Audiodateien eines Buchs (Bild, Text), gelesen über die Ordnerfreigabe ([LocalBookFolders]). */
data class SideFile(val name: String, val uri: String, val sizeBytes: Long, val modifiedMs: Long)

/**
 * Was die Ordner über ein Buch sagen, nach dem Aufbau, den Audiobookshelf erwartet (`books_improvements.md` M7):
 * `Audiobooks/Autor/Titel/` bzw. `Audiobooks/Autor/Serie/1 – Titel/`. Der Ordner des Buchs kann außerdem ein Jahr
 * („1994 – Titel“) und den Sprecher in geschweiften Klammern („Titel {Sprecher}“) tragen. Ein Band steht nur im Namen, wenn
 * es einen Serienordner gibt („1984“ allein ist ein Titel).
 */
internal data class FolderMetadata(
    val author: String? = null,
    val series: String? = null,
    val sequence: String? = null,
    val title: String? = null,
    val narrator: String? = null,
    val year: Int? = null,
) {
    /** Serie im Format von Audiobookshelf („Name #2“), wie `books.series` sie hält. */
    val seriesEntry: String? get() = series?.let { name -> sequence?.let { "$name #$it" } ?: name }

    companion object {
        // ICU (Android) verlangt auch die schließende Klammer escaped.
        private val NARRATOR = Regex("""\s*\{([^\{\}]+)\}\s*$""")
        private val SEQUENCE = Regex(
            """^(?:(?:vol(?:ume)?|band|book|buch|teil|folge|part|nr|no)\.?\s*)?(\d{1,4}(?:[.,]\d+)?)\s*(?:[-–—.:]\s+|\s+[-–—]\s*)(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val YEAR = Regex("""^((?:1[5-9]|20)\d\d)\s*[-–—]\s+(.+)$""")

        /** Aus dem Schlüssel eines Ordnerbuchs („Audiobooks/A/S/1 – T/“); eine Datei direkt in „Audiobooks“ hat keinen Ordner. */
        fun parse(key: String): FolderMetadata {
            if (!key.endsWith('/')) return FolderMetadata()
            val segments = key.trimEnd('/').split('/').drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) return FolderMetadata()
            val author = segments.takeIf { it.size >= 2 }?.first()
            val series = segments.takeIf { it.size >= 3 }?.get(segments.size - 2)
            var rest = segments.last()
            val narrator = NARRATOR.find(rest)?.groupValues?.get(1)?.trim()
            rest = rest.replace(NARRATOR, "").trim()
            var sequence: String? = null
            if (series != null) {
                SEQUENCE.matchEntire(rest)?.let { match ->
                    sequence = match.groupValues[1].replace(',', '.').trimStart('0').ifEmpty { "0" }.let { if (it.startsWith('.')) "0$it" else it }
                    rest = match.groupValues[2].trim()
                }
            }
            var year: Int? = null
            YEAR.matchEntire(rest)?.let { match ->
                year = match.groupValues[1].toInt()
                rest = match.groupValues[2].trim()
            }
            return FolderMetadata(author, series, sequence, rest.ifEmpty { null }, narrator, year)
        }
    }
}

/**
 * Die Begleitdateien eines Buchordners: Cover (`cover.*`, `folder.*`, sonst das erste Bild), `desc.txt` (Beschreibung),
 * `reader.txt` (Sprecher) und `metadata.opf` (sonst die einzige `.opf`-Datei).
 */
internal data class BookSidecars(
    val cover: SideFile? = null,
    val description: SideFile? = null,
    val reader: SideFile? = null,
    val opf: SideFile? = null,
) {
    /** Ändert sich, wenn eine der Dateien hinzukommt, wegfällt oder sich ändert. */
    val fingerprint: Long
        get() = listOfNotNull(cover, description, reader, opf).fold(17L) { hash, file ->
            31 * hash + "${file.name}|${file.sizeBytes}|${file.modifiedMs}".hashCode()
        }

    companion object {
        private val IMAGE = Regex("""(?i).+\.(jpe?g|png|webp)$""")
        private val NAMED_COVER = Regex("""(?i)(cover|folder)\.(jpe?g|png|webp)$""")

        fun pick(files: List<SideFile>): BookSidecars {
            val sorted = files.sortedWith { a, b -> LocalBookLayout.naturalCompare(a.name, b.name) }
            val images = sorted.filter { IMAGE.matches(it.name) }
            val cover = images.sortedBy { if (it.name.startsWith("cover", ignoreCase = true)) 0 else 1 }.firstOrNull { NAMED_COVER.matches(it.name) }
                ?: images.firstOrNull()
            val opfFiles = sorted.filter { it.name.endsWith(".opf", ignoreCase = true) }
            return BookSidecars(
                cover = cover,
                description = sorted.firstOrNull { it.name.equals("desc.txt", ignoreCase = true) },
                reader = sorted.firstOrNull { it.name.equals("reader.txt", ignoreCase = true) },
                opf = opfFiles.firstOrNull { it.name.equals("metadata.opf", ignoreCase = true) } ?: opfFiles.singleOrNull(),
            )
        }
    }
}

/** Was eine `metadata.opf` (Calibre, Audiobookshelf, EPUB 3) über ein Buch sagt; Leeres fehlt. */
internal data class OpfMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val description: String? = null,
    val publisher: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    /** Serien mit Band (`null` = ohne). */
    val series: List<Pair<String, String?>> = emptyList(),
) {
    /** Serien im Format von Audiobookshelf („Name #2, Andere #1“). */
    val seriesEntry: String? get() = series.joinToString(", ") { (name, seq) -> seq?.let { "$name #$it" } ?: name }.ifEmpty { null }

    companion object {
        private val YEAR = Regex("""^\d{4}""")
        private val ISBN = Regex("""^(?:97[89])?\d{9}[\dXx]$""")

        /** `null`, wenn [text] kein lesbares XML mit `metadata` ist. */
        fun parse(text: String): OpfMetadata? = try {
            read(text)
        } catch (_: XmlPullParserException) {
            null
        } catch (_: IOException) {
            null
        }

        private class Creator(val name: String, var role: String?)

        private fun read(text: String): OpfMetadata? {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            // Ohne Namensräume: Calibre schreibt `dc:`/`opf:`, andere setzen den Namensraum als Standard.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(text.removePrefix("\uFEFF")))
            val values = mutableMapOf<String, String>()
            val titles = mutableListOf<Pair<String?, String>>()
            val creators = mutableListOf<Creator>()
            val creatorIds = mutableMapOf<String, Creator>()
            val subjects = mutableListOf<String>()
            val identifiers = mutableListOf<Pair<String?, String>>()
            val calibre = mutableMapOf<String, String>()
            val collections = linkedMapOf<String, String>()
            val refines = mutableListOf<Triple<String, String, String>>()
            var hasMetadata = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.substringAfter(':').lowercase()
                    val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it).substringAfter(':').lowercase() to parser.getAttributeValue(it) }
                    when (name) {
                        "metadata" -> hasMetadata = true
                        "meta" -> {
                            val metaName = attributes["name"]
                            val property = attributes["property"]
                            if (metaName != null) {
                                attributes["content"]?.trim()?.takeIf { it.isNotEmpty() }?.let { calibre[metaName.lowercase()] = it }
                            } else if (property != null) {
                                val value = parser.nextText().trim()
                                val refined = attributes["refines"]?.removePrefix("#")
                                when {
                                    refined != null -> refines += Triple(refined, property.lowercase(), value)
                                    property == "belongs-to-collection" && value.isNotEmpty() -> collections[attributes["id"] ?: value] = value
                                }
                            }
                        }
                        "creator" -> {
                            val value = parser.nextText().trim()
                            if (value.isNotEmpty()) {
                                val creator = Creator(value, attributes["role"]?.lowercase())
                                creators += creator
                                attributes["id"]?.let { creatorIds[it] = creator }
                            }
                        }
                        "subject" -> parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { subjects += it }
                        "identifier" -> parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { identifiers += attributes["scheme"] to it }
                        "title" -> parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { titles += attributes["id"] to it }
                        "description", "publisher", "date", "language" ->
                            parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { values.putIfAbsent(name, it) }
                    }
                }
                event = parser.next()
            }
            if (!hasMetadata) return null
            // EPUB 3: Rollen und Bandnummern stehen in eigenen `meta refines="#id"`.
            val positions = mutableMapOf<String, String>()
            val subtitleIds = mutableSetOf<String>()
            for ((id, property, value) in refines) {
                when (property) {
                    "role" -> creatorIds[id]?.let { it.role = value.lowercase() }
                    "group-position" -> positions[id] = value
                    "title-type" -> if (value.equals("subtitle", ignoreCase = true)) subtitleIds += id
                }
            }
            val (subtitles, mainTitles) = titles.partition { it.first != null && it.first in subtitleIds }
            val series = buildList {
                calibre["calibre:series"]?.let { add(it to calibre["calibre:series_index"]?.let(::sequence)) }
                collections.forEach { (id, name) -> if (none { it.first == name }) add(name to positions[id]?.let(::sequence)) }
            }
            return OpfMetadata(
                title = mainTitles.firstOrNull()?.second,
                subtitle = subtitles.firstOrNull()?.second ?: calibre["calibre:subtitle"],
                authors = creators.filter { it.role == null || it.role == "aut" }.map { it.name },
                narrators = creators.filter { it.role == "nrt" }.map { it.name },
                description = values["description"],
                publisher = values["publisher"],
                year = values["date"]?.let { YEAR.find(it)?.value?.toIntOrNull() },
                language = values["language"],
                genres = subjects,
                isbn = identifiers.firstNotNullOfOrNull { (scheme, value) ->
                    val plain = value.removePrefix("urn:isbn:").replace("-", "").trim()
                    plain.takeIf { scheme.equals("ISBN", ignoreCase = true) || ISBN.matches(it) }
                },
                series = series,
            )
        }

        /** „2.0“ → „2“, „1.5“ bleibt. */
        private fun sequence(raw: String): String? {
            val value = raw.trim().replace(',', '.').ifEmpty { return null }
            return value.toDoubleOrNull()?.let { if (it == Math.floor(it)) it.toLong().toString() else value } ?: value
        }
    }
}
