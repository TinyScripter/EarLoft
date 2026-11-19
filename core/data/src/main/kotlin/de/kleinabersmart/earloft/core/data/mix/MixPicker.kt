package de.kleinabersmart.earloft.core.data.mix

import de.kleinabersmart.earloft.core.database.model.MixCandidateRow
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * What the mix looks for, derived from the songs of the starting point: [genres] (lowercase) weighted 0–1 (the
 * most frequent 1), the artists and the median year.
 */
internal data class MixProfile(
    val genres: Map<String, Double>,
    val artistIds: Set<Long>,
    val year: Int?,
) {
    companion object {
        fun of(seed: List<MixCandidateRow>, artistIds: Set<Long>? = null): MixProfile {
            val counts = seed.flatMap { it.genreKeys }.groupingBy { it }.eachCount()
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

/** The song's genres as keys (lowercase, without duplicates). */
internal val MixCandidateRow.genreKeys: List<String>
    get() = genres?.split(MixCandidateRow.GENRE_SEPARATOR)?.mapNotNull { it.genreKey() }?.distinct().orEmpty()

/**
 * Builds a mix from candidates (pure, no database; concept 8 "Mix"). Points are awarded for the genre of the
 * starting point, a nearby year, the same artists, suggestions from the server ([similar], the first ones most),
 * favorites and frequently played songs; recently played songs lose points, a bit of randomness mixes things up.
 *
 * Selection happens in rounds, each by points: first the matching songs (server suggestion, otherwise same genre; with no genres
 * in the starting point, nearby year or same artist) with at most [MAX_PER_ALBUM] per album and [MAX_PER_ARTIST] per
 * artist (those of the starting point [seedArtistLimit]), then the same with doubled limits, then all with single limits, finally
 * without limits. This way a small library would rather fill the mix with more of the same genre than with unrelated songs from the same
 * year. The selection comes shuffled, avoiding the same artist twice in a row where possible; [first] starts.
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
        /** [factor] multiplies the limits per album and artist; 0 = no limits. */
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
        // Several genres: the best-matching one counts.
        row.genreKeys.maxOfOrNull { profile.genres[it] ?: 0.0 }?.let { score += GENRE_WEIGHT * it }
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

    /** Matches the starting point: suggested by the server, otherwise same genre; with no genres in the profile, nearby year or same artist. */
    private fun MixCandidateRow.matches(profile: MixProfile, similarRank: Map<Long, Int>): Boolean {
        if (songId in similarRank) return true
        if (profile.genres.isNotEmpty()) return genreKeys.any { it in profile.genres }
        val year = year
        val artist = artistId
        return (profile.year != null && year != null && abs(year - profile.year) <= YEAR_SPAN) ||
            (artist != null && artist in profile.artistIds) ||
            profile.year == null
    }

    /** Orders so that the same artist does not come twice in a row where possible; otherwise the order stays. */
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
