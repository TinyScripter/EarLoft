package de.kleinabersmart.earloft.core.data.mix

import de.kleinabersmart.earloft.core.database.model.MixCandidateRow
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Wonach der Mix sucht, abgeleitet aus den Songs des Ausgangspunkts: [genres] (klein geschrieben) mit Gewicht 0–1 (das
 * häufigste 1), die Künstler und das mittlere Jahr.
 */
internal data class MixProfile(
    val genres: Map<String, Double>,
    val artistIds: Set<Long>,
    val year: Int?,
) {
    companion object {
        fun of(seed: List<MixCandidateRow>, artistIds: Set<Long>? = null): MixProfile {
            val counts = seed.mapNotNull { it.genre?.genreKey() }.groupingBy { it }.eachCount()
            val max = counts.values.maxOrNull() ?: 1
            val years = seed.mapNotNull { it.year }.sorted()
            return MixProfile(
                genres = counts.mapValues { (_, count) -> count.toDouble() / max },
                artistIds = artistIds ?: seed.mapNotNullTo(mutableSetOf()) { it.artistId },
                year = years.getOrNull(years.size / 2),
            )
        }
    }
}

internal fun String.genreKey(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

/**
 * Stellt aus Kandidaten einen Mix zusammen (rein, ohne Datenbank; Konzept 8 „Mix“). Punkte gibt es für das Genre des
 * Ausgangspunkts, ein nahes Jahr, dieselben Künstler, Vorschläge des Servers ([similar], die ersten am meisten),
 * Favoriten und oft Gehörtes; kürzlich Gehörtes verliert, etwas Zufall mischt.
 *
 * Gewählt wird in Runden, jeweils nach Punkten: erst die passenden Songs (Server-Vorschlag, sonst gleiches Genre; ohne Genres
 * im Ausgangspunkt nahes Jahr oder gleicher Künstler) mit höchstens [MAX_PER_ALBUM] je Album und [MAX_PER_ARTIST] je
 * Künstler (die des Ausgangspunkts [seedArtistLimit]), dann dieselben mit doppelten Grenzen, dann alle mit einfachen, zuletzt
 * ohne Grenzen. So füllt eine kleine Bibliothek den Mix lieber mit mehr vom selben Genre als mit Fremdem aus demselben
 * Jahr. Die Auswahl kommt gemischt, möglichst ohne denselben Künstler zweimal hintereinander; [first] beginnt.
 */
internal object MixPicker {

    const val MAX_PER_ALBUM = 2
    const val MAX_PER_ARTIST = 3
    const val YEAR_SPAN = 8

    private const val GENRE_WEIGHT = 3.0
    private const val YEAR_WEIGHT = 1.5
    private const val ARTIST_WEIGHT = 1.0
    private const val SIMILAR_BASE = 2.0
    private const val SIMILAR_WEIGHT = 3.0
    private const val FAVORITE_BONUS = 0.5
    private const val PLAY_BONUS = 0.05
    private const val MAX_COUNTED_PLAYS = 10
    private const val NOISE = 1.5
    private val PLAYED_TODAY_MS = TimeUnit.DAYS.toMillis(1)
    private val PLAYED_THIS_WEEK_MS = TimeUnit.DAYS.toMillis(7)

    fun pick(
        profile: MixProfile,
        candidates: Collection<MixCandidateRow>,
        similar: List<Long>,
        first: MixCandidateRow?,
        size: Int,
        seedArtistLimit: Int,
        now: Long,
        random: Random,
    ): List<Long> {
        val similarRank = similar.withIndex().associate { (index, songId) -> songId to index }
        val ranked = candidates
            .distinctBy { it.songId }
            .filter { it.songId != first?.songId }
            .map { it to score(it, profile, similarRank, similar.size, now, random) }
            .sortedByDescending { it.second }
            .map { it.first }

        val picked = mutableListOf<MixCandidateRow>()
        val chosen = mutableSetOf<Long>()
        val perAlbum = mutableMapOf<Long, Int>()
        val perArtist = mutableMapOf<Long, Int>()
        fun take(row: MixCandidateRow) {
            picked += row
            chosen += row.songId
            perAlbum.merge(row.albumId, 1, Int::plus)
            row.artistId?.let { perArtist.merge(it, 1, Int::plus) }
        }
        /** [factor] vervielfacht die Grenzen je Album und Künstler; 0 = keine Grenzen. */
        fun fill(rows: List<MixCandidateRow>, factor: Int) {
            for (row in rows) {
                if (picked.size >= size) return
                if (row.songId in chosen) continue
                if (factor > 0) {
                    if ((perAlbum[row.albumId] ?: 0) >= MAX_PER_ALBUM * factor) continue
                    val artist = row.artistId
                    if (artist != null) {
                        val limit = if (artist in profile.artistIds) seedArtistLimit else MAX_PER_ARTIST
                        if ((perArtist[artist] ?: 0) >= limit * factor) continue
                    }
                }
                take(row)
            }
        }
        first?.let(::take)
        val matching = ranked.filter { it.matches(profile, similarRank) }
        fill(matching, factor = 1)
        fill(matching, factor = 2)
        fill(ranked, factor = 1)
        fill(ranked, factor = 0)

        val rest = picked.drop(if (first != null) 1 else 0).shuffled(random)
        return (listOfNotNull(first) + spreadArtists(rest, first?.artistId)).map { it.songId }
    }

    private fun score(
        row: MixCandidateRow,
        profile: MixProfile,
        similarRank: Map<Long, Int>,
        similarCount: Int,
        now: Long,
        random: Random,
    ): Double {
        var score = 0.0
        row.genre?.genreKey()?.let { score += GENRE_WEIGHT * (profile.genres[it] ?: 0.0) }
        val year = row.year
        if (profile.year != null && year != null) {
            score += YEAR_WEIGHT * (1.0 - abs(year - profile.year).toDouble() / YEAR_SPAN).coerceAtLeast(0.0)
        }
        val artist = row.artistId
        if (artist != null && artist in profile.artistIds) score += ARTIST_WEIGHT
        similarRank[row.songId]?.let { score += SIMILAR_BASE + SIMILAR_WEIGHT * (1.0 - it.toDouble() / similarCount) }
        if (row.isFavorite) score += FAVORITE_BONUS
        score += PLAY_BONUS * row.playCount.coerceAtMost(MAX_COUNTED_PLAYS)
        row.lastPlayedAt?.let { playedAt ->
            val age = now - playedAt
            if (age < PLAYED_TODAY_MS) score -= 3.0 else if (age < PLAYED_THIS_WEEK_MS) score -= 1.0
        }
        return score + random.nextDouble() * NOISE
    }

    /** Passt zum Ausgangspunkt: vom Server vorgeschlagen, sonst gleiches Genre; ohne Genres im Profil nahes Jahr oder gleicher Künstler. */
    private fun MixCandidateRow.matches(profile: MixProfile, similarRank: Map<Long, Int>): Boolean {
        if (songId in similarRank) return true
        if (profile.genres.isNotEmpty()) return genre?.genreKey()?.let { it in profile.genres } == true
        val year = year
        val artist = artistId
        return (profile.year != null && year != null && abs(year - profile.year) <= YEAR_SPAN) ||
            (artist != null && artist in profile.artistIds) ||
            profile.year == null
    }

    /** Ordnet so, dass derselbe Künstler möglichst nicht zweimal hintereinander kommt; sonst bleibt die Reihenfolge. */
    private fun spreadArtists(rows: List<MixCandidateRow>, previousArtist: Long?): List<MixCandidateRow> {
        val pending = rows.toMutableList()
        val result = ArrayList<MixCandidateRow>(rows.size)
        var previous = previousArtist
        while (pending.isNotEmpty()) {
            val index = pending.indexOfFirst { it.artistId == null || it.artistId != previous }.takeIf { it >= 0 } ?: 0
            val next = pending.removeAt(index)
            result += next
            previous = next.artistId
        }
        return result
    }
}
