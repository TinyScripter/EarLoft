package de.kleinabersmart.earloft.core.datasource.local

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/**
 * What an episode or book says about itself that MediaStore doesn't provide (concept 12.1): publication date,
 * description, subtitle and chapters (ID3 `CHAP`). Empty values are `null`; the chapters are ordered by start.
 */
internal data class SpokenTags(
    val date: String? = null,
    val description: String? = null,
    val subtitle: String? = null,
    val chapters: List<LocalBookChapter> = emptyList(),
    /** Series and volume as Audiobookshelf reads them (`SERIES`/`SERIES-PART`, ID3 `MVNM`/`MVIN`). */
    val series: String? = null,
    val seriesPart: String? = null,
) {
    /** The year at the start of [date] ("2024", "2024-05-01"). */
    val year: Int? get() = date?.let { YEAR.find(it.trim())?.value?.toIntOrNull() }

    companion object {
        /** Shorter than this is not a chapter but a cut mark (the jingle marks of some broadcasters). */
        private const val MIN_CHAPTER_MS = 1_000L

        private val DATE_FRAMES = listOf("TDRC", "TDRL")
        private val DAY_MONTH = Regex("""(\d{2})(\d{2})""")

        /** ID3v2.3 separates year (`TYER`) and day/month (`TDAT`, "DDMM"); without `TDAT` the year stays. */
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
                        // Free-form iTunes fields of an MP4 file (`----:com.apple.iTunes:SUBTITLE`).
                        is InternalFrame -> if (entry.text.isNotBlank()) descriptions.putIfAbsent(entry.description.uppercase(), entry.text.trim())
                        is ChapterFrame -> chapters += chapterOf(entry)
                    }
                }
            }
            return SpokenTags(
                date = DATE_FRAMES.firstNotNullOfOrNull { frames[it] } ?: v23Date(frames) ?: descriptions["DATE"],
                description = (DESCRIPTION_FRAMES.firstNotNullOfOrNull { frames[it] } ?: descriptions["DESCRIPTION"])?.takeIf { it.isNotBlank() },
                subtitle = (frames["TIT3"] ?: descriptions["SUBTITLE"])?.trim()?.takeIf { it.isNotEmpty() },
                series = (descriptions["SERIES"] ?: frames["MVNM"])?.trim()?.takeIf { it.isNotEmpty() },
                // "2/7" for MVIN: only the volume.
                seriesPart = (descriptions["SERIES-PART"] ?: frames["MVIN"])?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() },
                chapters = chapters.filter { it.endMs - it.startMs >= MIN_CHAPTER_MS }.sortedBy { it.startMs }.plausible(),
            )
        }

        /** Title without content: the chapter's ID ("ch3", "c4") or just a number. */
        private val GENERIC_TITLE = Regex("""(?i)(?:c|ch|chp|chap|chapter|kapitel)?[ _-]?(\d+)""")
        private const val OVERLAP_TOLERANCE_MS = 1_000L

        /**
         * Some broadcasters (on the emulator: ARD) leave cut marks of their production system in the file as ID3 chapters:
         * nested ("ch1" … "ch9" within one minute) or almost without names. Such lists are not chapters and
         * are dropped entirely, instead of letting the chapter buttons jump to cut marks. Consecutively numbered chapters from the
         * start of the file ("Chapter 1" …, "Kapitel 1" …, "001" …, as tools write them into audiobooks) stay.
         */
        private fun List<LocalBookChapter>.plausible(): List<LocalBookChapter> {
            val overlapping = zipWithNext().any { (a, b) -> b.startMs < a.endMs - OVERLAP_TOLERANCE_MS }
            if (overlapping) return emptyList()
            val numbers = mapNotNull { GENERIC_TITLE.matchEntire(it.title.trim())?.groupValues?.get(1)?.toIntOrNull() }
            val named = size - numbers.size
            val numbered = numbers.size >= 2 && numbers.zipWithNext().all { (a, b) -> b == a + 1 } &&
                first().startMs <= OVERLAP_TOLERANCE_MS
            return if (named * 2 < size && !numbered) emptyList() else this
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
