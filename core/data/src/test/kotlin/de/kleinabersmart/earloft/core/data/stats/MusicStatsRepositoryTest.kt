package de.kleinabersmart.earloft.core.data.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class MusicStatsRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var stats: MusicStatsRepository
    private val zone = ZoneOffset.ofHours(1)
    private val today = LocalDate.of(2026, 11, 13)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stats = MusicStatsRepository(db.libraryDao())
    }

    @After
    fun tearDown() = db.close()

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `totals, months, weekdays and new albums follow the chosen year`() = runTest {
        db.sourceDao().upsert(SourceEntity(sourceId = "local", type = SourceType.LOCAL, name = "Device"))
        val library = db.libraryDao()
        val old = library.insertAlbum(album("Old"))
        val new = library.insertAlbum(album("New"))
        val a = library.insertSong(song(old, "A"))
        val b = library.insertSong(song(old, "B"))
        val c = library.insertSong(song(new, "C"))
        listOf(a, b, c).forEach { library.insertSongSource(SongSourceEntity(uid = "local:$it", songId = it, sourceId = "local", remoteId = "$it", seenInGeneration = 1)) }
        fun play(songId: Long, time: String, minutes: Long) =
            PlayEventEntity(songId = songId, playedAt = at(time), listenedMs = minutes * 60_000)
        listOf(
            play(a, "2025-12-31T23:30:00", 4), // still 2025 in zone +1
            play(a, "2026-01-05T10:00:00", 3), // Monday
            play(b, "2026-01-05T11:00:00", 5),
            play(c, "2026-11-01T00:30:00", 2), // Sunday
        ).forEach { db.playbackDao().insertPlayEventIfSongExists(it) }

        val all = stats.musicStats(zone = zone, today = { today }).first()
        assertEquals(4, all.plays)
        assertEquals(14 * 60_000L, all.totalMs)
        assertEquals(3, all.songs)
        assertEquals(2, all.albums)
        assertEquals(2, all.newAlbums)
        assertEquals(listOf(2026, 2025), all.years)
        // Last twelve months: December 2025 to November 2026.
        assertEquals(12, all.months.size)
        assertEquals(4 * 60_000L, all.months.first().listenedMs)
        assertEquals(2 * 60_000L, all.months.last().listenedMs)
        assertEquals("A", all.topSongs.first().title)

        val year = stats.musicStats(year = 2026, zone = zone, today = { today }).first()
        assertEquals(3, year.plays)
        assertEquals(10 * 60_000L, year.totalMs)
        assertEquals(3, year.songs)
        assertEquals(2, year.albums)
        // "Old" already played in 2025, only "New" is new.
        assertEquals(1, year.newAlbums)
        assertEquals(8 * 60_000L, year.weekdays.first().listenedMs)
        assertEquals(2 * 60_000L, year.weekdays.last().listenedMs)
        assertEquals(listOf(8 * 60_000L, 2 * 60_000L), year.months.filter { it.listenedMs > 0 }.map { it.listenedMs })
        assertEquals(listOf("A", "B", "C").sorted(), year.topSongs.map { it.title }.sorted())
        assertTrue(stats.musicStats(year = 2024, zone = zone, today = { today }).first().topSongs.isEmpty())
    }

    private fun album(title: String) = AlbumEntity(
        title = title,
        sortTitle = title,
        normTitle = title.lowercase(),
        albumArtistDisplay = "Artist",
        normAlbumArtist = "artist",
    )

    private fun song(albumId: Long, title: String) = SongEntity(
        albumId = albumId,
        title = title,
        normTitle = title.lowercase(),
        artistDisplay = "Artist",
        trackNumber = 1,
        durationMs = 300_000,
        dateAdded = 0,
    )
}
