package de.kleinabersmart.earloft.core.datasource.local

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/**
 * Was eine Folge oder ein Buch über sich selbst sagt und MediaStore nicht liefert (Konzept 12.1): Erscheinungsdatum,
 * Beschreibung, Untertitel und Kapitel (ID3 `CHAP`). Leere Werte sind `null`; die Kapitel sind nach Beginn geordnet.
 */
internal data class SpokenTags(
    val date: String? = null,
    val description: String? = null,
    val subtitle: String? = null,
    val chapters: List<LocalBookChapter> = emptyList(),
) {
    /** Das Jahr am Anfang von [date] („2024“, „2024-05-01“). */
    val year: Int? get() = date?.let { YEAR.find(it.trim())?.value?.toIntOrNull() }

    companion object {
        /** Kürzer als das ist kein Kapitel, sondern eine Schnittmarke (die Jingle-Marken mancher Sender). */
        private const val MIN_CHAPTER_MS = 1_000L

        private val DATE_FRAMES = listOf("TDRC", "TDRL")
        private val DAY_MONTH = Regex("""(\d{2})(\d{2})""")

        /** ID3v2.3 trennt Jahr (`TYER`) und Tag/Monat (`TDAT`, „DDMM“); ohne `TDAT` bleibt das Jahr. */
        private fun v23Date(frames: Map<String, String>): String? {
            val year = frames["TYER"] ?: return null
            val (day, month) = frames["TDAT"]?.let { DAY_MONTH.matchEntire(it.trim())?.destructured } ?: return year
            return "$year-$month-$day"
        }
        private val DESCRIPTION_FRAMES = listOf("TDES")
        private val YEAR = Regex("""^\d{4}(?!\d)""")

        @OptIn(UnstableApi::class)
        fun from(metadata: List<Metadata>): SpokenTags {
            val frames = mutableMapOf<String, String>()
            val descriptions = mutableMapOf<String, String>()
            val chapters = mutableListOf<LocalBookChapter>()
            for (block in metadata) {
                for (i in 0 until block.length()) {
                    when (val entry = block[i]) {
                        is TextInformationFrame -> {
                            val value = entry.values.firstOrNull()?.trim().orEmpty()
                            if (value.isEmpty()) continue
                            if (entry.id == "TXXX") {
                                entry.description?.let { descriptions.putIfAbsent(it.uppercase(), value) }
                            } else {
                                frames.putIfAbsent(entry.id, value)
                            }
                        }
                        is VorbisComment -> descriptions.putIfAbsent(entry.key.uppercase(), entry.value)
                        // Freie iTunes-Felder einer MP4-Datei (`----:com.apple.iTunes:SUBTITLE`).
                        is InternalFrame -> if (entry.text.isNotBlank()) descriptions.putIfAbsent(entry.description.uppercase(), entry.text.trim())
                        is ChapterFrame -> chapters += chapterOf(entry)
                    }
                }
            }
            return SpokenTags(
                date = DATE_FRAMES.firstNotNullOfOrNull { frames[it] } ?: v23Date(frames) ?: descriptions["DATE"],
                description = (DESCRIPTION_FRAMES.firstNotNullOfOrNull { frames[it] } ?: descriptions["DESCRIPTION"])?.takeIf { it.isNotBlank() },
                subtitle = (frames["TIT3"] ?: descriptions["SUBTITLE"])?.trim()?.takeIf { it.isNotEmpty() },
                chapters = chapters.filter { it.endMs - it.startMs >= MIN_CHAPTER_MS }.sortedBy { it.startMs }.plausible(),
            )
        }

        /** Titel ohne Inhalt: die Kennung des Kapitels („ch3“, „c4“) oder nur eine Zahl. */
        private val GENERIC_TITLE = Regex("""(?i)(c|ch|chp|chap|chapter|kapitel)?[ _-]?\d+""")
        private const val OVERLAP_TOLERANCE_MS = 1_000L

        /**
         * Manche Sender (am Emulator: ARD) lassen Schnittmarken ihres Produktionssystems als ID3-Kapitel in der Datei:
         * verschachtelt („ch1“ … „ch9“ innerhalb einer Minute) oder fast ohne Namen. Solche Listen sind keine Kapitel und
         * fallen ganz weg, statt die Kapitelknöpfe auf Schnittmarken springen zu lassen.
         */
        private fun List<LocalBookChapter>.plausible(): List<LocalBookChapter> {
            val overlapping = zipWithNext().any { (a, b) -> b.startMs < a.endMs - OVERLAP_TOLERANCE_MS }
            val named = count { !GENERIC_TITLE.matches(it.title.trim()) }
            return if (overlapping || named * 2 < size) emptyList() else this
        }

        @OptIn(UnstableApi::class)
        private fun chapterOf(frame: ChapterFrame): LocalBookChapter {
            val title = (0 until frame.subFrameCount)
                .map { frame.getSubFrame(it) }
                .filterIsInstance<TextInformationFrame>()
                .firstOrNull { it.id == "TIT2" }
                ?.values?.firstOrNull()?.trim()
            return LocalBookChapter(title?.takeIf { it.isNotEmpty() } ?: frame.chapterId, frame.startTimeMs.toLong(), frame.endTimeMs.toLong())
        }
    }
}
