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
 * wie viele beendet sind und mit welchem Band es weitergeht ([next] samt Band, wie er dasteht; `null`, wenn alle beendet sind).
 */
data class SeriesProgress(
    val name: String,
    val books: List<BookWithProgress>,
    val finished: Int,
    val next: BookWithProgress?,
    val nextVolume: String?,
    val step: SeriesStep?,
    /** Zeitpunkt des zuletzt beendeten Bands (für „Serie fortsetzen“); 0 = keiner. */
    val lastFinishedAt: Long,
) {
    val total: Int get() = books.size
}

private val BookWithProgress.isStarted: Boolean
    get() = progress.let { it != null && !it.finished && it.positionMs > 0 }

private val BookWithProgress.isFinished: Boolean get() = progress?.finished == true

/** Stelle dieses Buchs in der Serie [name] ([de.kleinabersmart.earloft.core.model.volumeSequence]); `null` ohne Nummer. */
fun BookWithProgress.volumeIn(name: String): Double? = parseSeries(book.series).firstOrNull { it.name == name }?.sequence

/** Band dieses Buchs in der Serie [name], wie er dasteht („2a“); `null` ohne Band. */
fun BookWithProgress.volumeTextIn(name: String): String? = parseSeries(book.series).firstOrNull { it.name == name }?.volume

/**
 * Die Nummern der beendeten Bände von [name]: Dasselbe Buch aus einer zweiten Quelle (Server und Ordner auf dem Gerät) trägt
 * dieselbe Nummer und gilt mit dem beendeten als erledigt, auch wenn es selbst noch nicht angefangen ist.
 */
private fun List<BookWithProgress>.finishedVolumes(name: String): Set<Double> =
    filter { it.isFinished }.mapNotNullTo(HashSet()) { it.volumeIn(name) }

private fun BookWithProgress.isDone(name: String, finishedVolumes: Set<Double>): Boolean =
    isFinished || volumeIn(name)?.let { it in finishedVolumes } == true

/**
 * Stand der Serie [name] aus ihren Bänden [books] (in Reihenfolge). Weiter geht es mit dem zuletzt gehörten angefangenen
 * Band; ist keiner angefangen, mit dem ersten nicht beendeten nach dem zuletzt beendeten (gibt es dahinter keinen, mit einer
 * Lücke davor); ist nichts gehört, mit dem ersten.
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
 * höherer Nummer, samt seinem Band. `null` ohne Serie, ohne Nummer oder wenn es keinen gibt.
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
