package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.parseSeries

/** How a series continues ([SeriesProgress.next]). */
enum class SeriesStep {
    /** A volume has been started: continue with it. */
    CONTINUE,

    /** Volumes are finished, none started: the next one after the most recently finished. */
    NEXT,

    /** Nothing played yet: the first volume. */
    START,
}

/**
 * State of a series (`books_improvements.md` M2), purely local from the progress: the volumes in order ([seriesGroups]),
 * how many are finished and which volume comes next ([next] along with the volume as written; `null` if all are finished).
 */
data class SeriesProgress(
    val name: String,
    val books: List<BookWithProgress>,
    val finished: Int,
    val next: BookWithProgress?,
    val nextVolume: String?,
    val step: SeriesStep?,
    /** Time of the most recently finished volume (for "Continue series"); 0 = none. */
    val lastFinishedAt: Long,
) {
    val total: Int get() = books.size
}

private val BookWithProgress.isStarted: Boolean
    get() = progress.let { it != null && !it.finished && it.positionMs > 0 }

private val BookWithProgress.isFinished: Boolean get() = progress?.finished == true

/** Position of this book in series [name] ([de.kleinabersmart.earloft.core.model.volumeSequence]); `null` without a number. */
fun BookWithProgress.volumeIn(name: String): Double? = parseSeries(book.series).firstOrNull { it.name == name }?.sequence

/** Volume of this book in series [name] as written ("2a"); `null` without a volume. */
fun BookWithProgress.volumeTextIn(name: String): String? = parseSeries(book.series).firstOrNull { it.name == name }?.volume

/**
 * The numbers of the finished volumes of [name]: the same book from a second source (server and folder on the device) carries
 * the same number and counts as done along with the finished one, even if it hasn't been started itself.
 */
private fun List<BookWithProgress>.finishedVolumes(name: String): Set<Double> =
    filter { it.isFinished }.mapNotNullTo(HashSet()) { it.volumeIn(name) }

private fun BookWithProgress.isDone(name: String, finishedVolumes: Set<Double>): Boolean =
    isFinished || volumeIn(name)?.let { it in finishedVolumes } == true

/**
 * State of series [name] from its volumes [books] (in order). It continues with the most recently played started
 * volume; if none is started, with the first unfinished one after the most recently finished (if there's none after it, with a
 * gap before it); if nothing has been played, with the first one.
 */
fun seriesProgress(name: String, books: List<BookWithProgress>): SeriesProgress {
    val started = books.filter { it.isStarted }.maxByOrNull { it.progress?.updatedAt ?: 0 }
    val lastFinished = books.indexOfLast { it.isFinished }
    val finishedVolumes = books.finishedVolumes(name)
    val (next, step) = when {
        started != null -> started to SeriesStep.CONTINUE
        lastFinished >= 0 -> {
            val open = { item: BookWithProgress -> !item.isDone(name, finishedVolumes) }
            (books.drop(lastFinished + 1).firstOrNull(open) ?: books.firstOrNull(open)).let { it to it?.let { SeriesStep.NEXT } }
        }
        else -> books.firstOrNull() to books.firstOrNull()?.let { SeriesStep.START }
    }
    return SeriesProgress(
        name = name,
        books = books,
        finished = books.count { it.isFinished },
        next = next,
        nextVolume = next?.volumeTextIn(name),
        step = step,
        lastFinishedAt = books.filter { it.isFinished }.maxOfOrNull { it.progress?.updatedAt ?: 0 } ?: 0,
    )
}

/** State of all series, in the order of the groups. */
fun List<BookGroup>.seriesProgress(): List<SeriesProgress> = map { seriesProgress(it.name, it.books) }

/**
 * Home shelf "Continue series": per series with at least one finished and no started volume, the next volume,
 * most recently finished series first. A book that is next in several series appears once.
 */
fun List<BookWithProgress>.continueSeries(limit: Int = 12): List<Pair<BookWithProgress, SeriesProgress>> =
    seriesGroups().seriesProgress()
        .filter { it.step == SeriesStep.NEXT && it.next != null }
        .sortedByDescending { it.lastFinishedAt }
        .distinctBy { it.next!!.book.id }
        .take(limit)
        .map { it.next!! to it }

/**
 * The next volume after the finished book [current] (card in the player): in one of its series the first unfinished one with
 * a higher number, along with its volume. `null` without series, without number, or if there is none.
 */
fun nextInSeries(current: BookWithProgress, all: List<BookWithProgress>): Pair<BookWithProgress, String?>? {
    val groups = all.seriesGroups().associateBy { it.name }
    for (ref in parseSeries(current.book.series)) {
        val sequence = ref.sequence ?: continue
        val books = groups[ref.name]?.books ?: continue
        val finishedVolumes = books.finishedVolumes(ref.name)
        val next = books.firstOrNull { item ->
            val volume = item.volumeIn(ref.name)
            item.book.id != current.book.id && volume != null && volume > sequence && !item.isDone(ref.name, finishedVolumes)
        } ?: continue
        return next to next.volumeTextIn(ref.name)
    }
    return null
}
