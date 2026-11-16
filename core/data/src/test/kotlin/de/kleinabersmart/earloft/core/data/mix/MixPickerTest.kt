package de.kleinabersmart.earloft.core.data.mix

import de.kleinabersmart.earloft.core.database.model.MixCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MixPickerTest {

    private val now = TimeUnit.DAYS.toMillis(1000)

    @Test
    fun `profile weighs genres by share and takes the median year`() {
        val profile = MixProfile.of(
            listOf(
                row(1, genre = "Post-Rock", year = 2005, artist = 7),
                row(2, genre = "post-rock ", year = 2010, artist = 7),
                row(3, genre = "Ambient", year = 2002, artist = 8),
            ),
        )
        assertEquals(mapOf("post-rock" to 1.0, "ambient" to 0.5), profile.genres)
        assertEquals(2005, profile.year)
        assertEquals(setOf(7L, 8L), profile.artistIds)
    }

    @Test
    fun `starts with the seed and prefers the same genre over other music`() {
        val seed = row(1, genre = "Jazz", year = 1960, artist = 1, album = 1)
        val jazz = (10L until 30L).map { row(it, genre = "Jazz", year = 1962, artist = it, album = it) }
        val metal = (30L until 50L).map { row(it, genre = "Metal", year = 2015, artist = it, album = it) }

        val mix = pick(seed, jazz + metal, size = 10)

        assertEquals(1L, mix.first())
        assertEquals(10, mix.size)
        assertTrue(mix.drop(1).all { it in 10L until 30L })
    }

    @Test
    fun `a song with several genres matches through any of them`() {
        val seed = row(1, genre = "Hip-Hop", year = 1986, artist = 1, album = 1)
        val both = row(10, genre = "Rap${MixCandidateRow.GENRE_SEPARATOR}hip-hop ", year = 2015, artist = 10, album = 10)
        val metal = (30L until 40L).map { row(it, genre = "Metal", year = 2015, artist = it, album = it) }

        assertEquals(mapOf("rap" to 1.0, "hip-hop" to 1.0), MixProfile.of(listOf(both)).genres)
        // Platz für einen Song neben dem Ausgangspunkt: Der passende kommt vor allem Fremden.
        assertEquals(listOf(1L, 10L), pick(seed, metal + both, size = 2))
    }

    @Test
    fun `limits songs per album and per artist unless nothing else is left`() {
        val seed = row(1, genre = "Jazz", artist = 1, album = 1)
        val jazzArtists = (10L until 15L).map { row(it, genre = "Jazz", artist = it, album = it) }
        // Ein Album mit zehn Jazz-Songs eines Künstlers und zehn Songs anderer Künstler ohne Genre.
        val sameAlbum = (20L until 30L).map { row(it, genre = "Jazz", artist = 2, album = 2) }
        val others = (30L until 40L).map { row(it, artist = it, album = it) }

        // Erst je Album zwei, dann reichen die passenden nicht: doppelte Grenze vor Fremdem.
        val mix = pick(seed, jazzArtists + sameAlbum + others, size = 8)
        assertEquals(setOf(10L, 11L, 12L, 13L, 14L), mix.filter { it in 10L until 15L }.toSet())
        assertEquals(2, mix.count { it in 20L until 30L })
        val longer = pick(seed, jazzArtists + sameAlbum + others, size = 12)
        assertEquals(2 * MixPicker.MAX_PER_ALBUM, longer.count { it in 20L until 30L })
        assertEquals(2, longer.count { it in 30L until 40L })

        // Reicht der Rest nicht, wird ohne Grenzen aufgefüllt.
        val full = pick(seed, sameAlbum, size = 11)
        assertEquals(11, full.size)
    }

    @Test
    fun `server suggestions and old plays win over songs heard today`() {
        val seed = row(1, artist = 1, album = 1)
        val suggested = row(10, artist = 10, album = 10)
        val heardToday = row(11, genre = null, artist = 11, album = 11, lastPlayedAt = now - 1_000)
        val plain = (20L until 25L).map { row(it, artist = it, album = it) }

        val mix = pick(seed, listOf(suggested, heardToday) + plain, size = 3, similar = listOf(10L))

        assertTrue(10L in mix)
        assertTrue(11L !in mix)
    }

    @Test
    fun `avoids the same artist twice in a row when possible`() {
        val seed = row(1, genre = "Jazz", artist = 1, album = 1)
        val a = (10L until 13L).map { row(it, genre = "Jazz", artist = 2, album = it) }
        val b = (20L until 23L).map { row(it, genre = "Jazz", artist = 3, album = it) }
        val byId = (listOf(seed) + a + b).associateBy { it.songId }

        repeat(20) { attempt ->
            val mix = pick(seed, a + b, size = 7, random = Random(attempt))
            val artists = mix.map { byId.getValue(it).artistId }
            assertTrue("$artists", artists.zipWithNext().none { (x, y) -> x == y })
        }
    }

    private fun pick(
        seed: MixCandidateRow,
        candidates: List<MixCandidateRow>,
        size: Int,
        similar: List<Long> = emptyList(),
        random: Random = Random(42),
    ): List<Long> = MixPicker.pick(
        profile = MixProfile.of(listOf(seed)),
        candidates = candidates,
        similar = similar,
        first = seed,
        size = size,
        seedArtistLimit = MixPicker.MAX_PER_ARTIST,
        now = now,
        random = random,
    )

    private fun row(
        id: Long,
        genre: String? = null,
        year: Int? = null,
        artist: Long? = null,
        album: Long = 100,
        lastPlayedAt: Long? = null,
    ) = MixCandidateRow(
        songId = id,
        albumId = album,
        artistId = artist,
        genres = genre,
        year = year,
        isFavorite = false,
        playCount = 0,
        lastPlayedAt = lastPlayedAt,
    )
}
