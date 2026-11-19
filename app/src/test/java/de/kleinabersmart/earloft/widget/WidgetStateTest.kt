package de.kleinabersmart.earloft.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {

    private val song = NowPlaying(songId = 1, title = "Song", artist = "A", albumId = 10, isPlaying = false)
    private val episode = NowPlaying(songId = null, title = "Folge", artist = "P", albumId = null, isPlaying = true, bookId = 5, isEpisode = true)
    private val book = NowPlaying(songId = null, title = "Buch", artist = "B", albumId = null, isPlaying = false, bookId = 7)

    private val state = WidgetState(
        entries = mapOf(
            SpokenMode.MUSIC to WidgetEntry(song, lastActiveAt = 200),
            SpokenMode.PODCASTS to WidgetEntry(episode, lastActiveAt = 300),
            SpokenMode.BOOKS to WidgetEntry(book, lastActiveAt = 100),
        ),
        currentArea = SpokenMode.PODCASTS,
        clock = BookClock(bookId = 5, positionMs = 1_000, durationMs = 2_000, speed = 1f, running = true, measuredAtMs = 0),
    )

    @Test
    fun `a widget for the playing area shows what plays, with the running clock`() {
        val entry = state.entryFor(setOf(SpokenMode.PODCASTS, SpokenMode.BOOKS))!!
        assertEquals(episode, entry.nowPlaying)
        assertEquals(1_000L, entry.clock?.positionMs)
        assertTrue(state.isCurrent(entry))
    }

    @Test
    fun `a widget for other areas shows the last heard of them, not in the player`() {
        val entry = state.entryFor(setOf(SpokenMode.MUSIC, SpokenMode.BOOKS))!!
        assertEquals(song, entry.nowPlaying)
        assertFalse(state.isCurrent(entry))
    }

    @Test
    fun `a widget for an area that never played shows nothing`() {
        assertNull(WidgetState(entries = mapOf(SpokenMode.MUSIC to WidgetEntry(song))).entryFor(setOf(SpokenMode.BOOKS)))
    }

    @Test
    fun `a clock of another book stays stopped with its entry`() {
        val next = state.withClock(null, nowMs = 10_000)
        assertNull(next.clock)
        val stopped = next.entries.getValue(SpokenMode.PODCASTS).clock!!
        assertEquals(2_000L, stopped.positionMs) // 1 s + 10 s, at most the duration
        assertFalse(stopped.running)
    }

    @Test
    fun `the area follows what plays`() {
        assertEquals(SpokenMode.MUSIC, song.area)
        assertEquals(SpokenMode.PODCASTS, episode.area)
        assertEquals(SpokenMode.BOOKS, book.area)
    }

    @Test
    fun `the config survives the widget state, an unset one stays unset`() {
        val prefs = mutablePreferencesOf()
        assertNull(prefs.widgetConfig())
        val config = WidgetConfig(setOf(SpokenMode.PODCASTS, SpokenMode.BOOKS), WidgetColors.SYSTEM)
        prefs.setWidgetConfig(config)
        assertEquals(config, prefs.widgetConfig())
    }
}
