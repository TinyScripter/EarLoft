package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.parseSeries

/** Wie es in einer Serie weitergeht ([SeriesProgress.next]). */
enum class SeriesStep {
    /** Ein Band ist angefangen: mit ihm weiter. */
    CONTINUE,

    /** Bände sind beendet, keiner angefangen: der nächste nach dem zuletzt beendeten. */
    NEXT,

    /** Noch nichts gehört: der erste Band. */
    START,
}

/**
 * Stand einer Serie (`books_improvements.md` M2), rein lokal aus dem Fortschritt: die Bände in Reihenfolge ([seriesGroups]),
 * wie viele beendet sind und mit welchem Band es weitergeht ([next] samt Nummer; `null`, wenn alle beendet sind).
 */
data class SeriesProgress(
    val name: String,
    val books: List<BookWithProgress>,
    val finished: Int,
    val next: BookWithProgress?,
    val nextVolume: Double?,
    val step: SeriesStep?,
    /** Zeitpunkt des zuletzt beendeten Bands (für „Serie fortsetzen“); 0 = keiner. */
    val lastFinishedAt: Long,
) {
    val total: Int get() = books.size
}

private val BookWithProgress.isStarted: Boolean
    get() = progress.let { it != null && !it.finished && it.positionMs > 0 }

private val BookWithProgress.isFinished: Boolean get() = progress?.finished == true

/** Band dieses Buchs in der Serie [name]; `null` ohne Nummer. */
fun BookWithProgress.volumeIn(name: String): Double? = parseSeries(book.series).firstOrNull { it.name == name }?.sequence

/**
 * Stand der Serie [name] aus ihren Bänden [books] (in Reihenfolge). Weiter geht es mit dem zuletzt gehörten angefangenen
 * Band; ist keiner angefangen, mit dem ersten nicht beendeten nach dem zuletzt beendeten (gibt es dahinter keinen, mit einer
 * Lücke davor); ist nichts gehört, mit dem ersten.
 */
fun seriesProgress(name: String, books: List<BookWithProgress>): SeriesProgress {
    val started = books.filter { it.isStarted }.maxByOrNull { it.progress?.updatedAt ?: 0 }
    val lastFinished = books.indexOfLast { it.isFinished }
    val (next, step) = when {
        started != null -> started to SeriesStep.CONTINUE
        lastFinished >= 0 -> (books.drop(lastFinished + 1).firstOrNull { !it.isFinished } ?: books.firstOrNull { !it.isFinished })
            .let { it to it?.let { SeriesStep.NEXT } }
        else -> books.firstOrNull() to books.firstOrNull()?.let { SeriesStep.START }
    }
    return SeriesProgress(
        name = name,
        books = books,
        finished = books.count { it.isFinished },
        next = next,
        nextVolume = next?.volumeIn(name),
        step = step,
        lastFinishedAt = books.filter { it.isFinished }.maxOfOrNull { it.progress?.updatedAt ?: 0 } ?: 0,
    )
}

/** Stand aller Serien, in der Reihenfolge der Gruppen. */
fun List<BookGroup>.seriesProgress(): List<SeriesProgress> = map { seriesProgress(it.name, it.books) }

/**
 * Start-Regal „Serie fortsetzen“: je Serie mit mindestens einem beendeten und keinem angefangenen Band der nächste Band,
 * zuletzt beendete Serien zuerst. Ein Buch, das in mehreren Serien als nächstes dran ist, steht einmal.
 */
fun List<BookWithProgress>.continueSeries(limit: Int = 12): List<Pair<BookWithProgress, SeriesProgress>> =
    seriesGroups().seriesProgress()
        .filter { it.step == SeriesStep.NEXT && it.next != null }
        .sortedByDescending { it.lastFinishedAt }
        .distinctBy { it.next!!.book.id }
        .take(limit)
        .map { it.next!! to it }

/**
 * Der nächste Band nach dem beendeten Buch [current] (Karte im Player): in einer seiner Serien der erste nicht beendete mit
 * höherer Nummer. `null` ohne Serie, ohne Nummer oder wenn es keinen gibt.
 */
fun nextInSeries(current: BookWithProgress, all: List<BookWithProgress>): Pair<BookWithProgress, Double?>? {
    val groups = all.seriesGroups().associateBy { it.name }
    for (ref in parseSeries(current.book.series)) {
        val sequence = ref.sequence ?: continue
        val books = groups[ref.name]?.books ?: continue
        val next = books.firstOrNull { item ->
            val volume = item.volumeIn(ref.name)
            item.book.id != current.book.id && volume != null && volume > sequence && !item.isFinished
        } ?: continue
        return next to next.volumeIn(ref.name)
    }
    return null
}
