package de.kleinabersmart.earloft.wear.download

import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenProgressTest {

    private fun track(index: Int, durationMs: Long, state: TrackDownload = TrackDownload.QUEUED) =
        BookTrackEntity(7, index, "f$index", index * 1_000L, durationMs, downloadState = state)

    @Test
    fun `files count by duration, the running one by its bytes`() {
        // 1 h fertig, 1 h zur Hälfte, 2 h offen: 1,5 von 4 h.
        val tracks = listOf(track(0, 3_600_000, TrackDownload.DONE), track(1, 3_600_000, TrackDownload.RUNNING), track(2, 7_200_000))
        val progress = spokenProgress(tracks, SpokenFileProgress(7, 1, done = 50, total = 100))

        assertEquals(0.375f, progress, 1e-6f)
    }

    @Test
    fun `unknown size of the running file counts as nothing yet, no files as zero`() {
        val tracks = listOf(track(0, 600_000, TrackDownload.RUNNING))

        assertEquals(0f, spokenProgress(tracks, SpokenFileProgress(7, 0, done = 50, total = null)), 0f)
        assertEquals(0f, spokenProgress(emptyList(), null), 0f)
        assertEquals(1f, spokenProgress(listOf(track(0, 600_000, TrackDownload.DONE)), null), 0f)
    }
}
