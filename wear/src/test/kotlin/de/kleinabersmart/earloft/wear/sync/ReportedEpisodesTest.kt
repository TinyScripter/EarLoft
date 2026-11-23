package de.kleinabersmart.earloft.wear.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReportedEpisodesTest {

    private val reported = ReportedEpisodes(ApplicationProvider.getApplicationContext())

    @Test
    fun `one report marks progress, listening time and when the statistics were read`() {
        reported.markReported(
            progress = listOf(Triple("feed", "g1", 10L), Triple("abs", "li_1", 20L)),
            listening = listOf(Triple("https://example.org/feed", "g1", 60_000L)),
            checkedAt = 5_000,
        )

        assertEquals(10L, reported.progressAt("feed", "g1"))
        assertEquals(20L, reported.progressAt("abs", "li_1"))
        assertEquals(60_000L, reported.listenedMs("https://example.org/feed", "g1"))
        assertEquals(5_000L, reported.listeningCheckedAt())
    }

    @Test
    fun `progress of episodes no longer on the watch is forgotten, listening time stays`() {
        reported.markReported(
            progress = listOf(Triple("feed", "g1", 10L), Triple("feed", "gone", 20L)),
            listening = listOf(Triple("https://example.org/feed", "gone", 60_000L)),
            checkedAt = 5_000,
        )

        reported.retainProgress(listOf("feed" to "g1"))

        assertEquals(10L, reported.progressAt("feed", "g1"))
        assertEquals(0L, reported.progressAt("feed", "gone"))
        // The statistics of the watch still hold its time: forgetting it would report it twice.
        assertEquals(60_000L, reported.listenedMs("https://example.org/feed", "gone"))
    }
}
