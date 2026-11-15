package de.kleinabersmart.earloft.core.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Test

class HeardCleanupTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    /** Geladen vor 10 Tagen, zu Ende gehört vor [daysAgo] Tagen. */
    private fun heard(id: Long, daysAgo: Long) = HeardDownload(id, finishedAt = now - daysAgo * day, downloadedAt = now - 10 * day)

    @Test
    fun `never keeps everything`() {
        assertEquals(emptyList<Long>(), expiredHeardDownloads(listOf(heard(1, 50)), PodcastSettings.DELETE_HEARD_NEVER, now))
    }

    @Test
    fun `immediately takes every episode finished after its download`() {
        assertEquals(listOf(1L, 2L), expiredHeardDownloads(listOf(heard(1, 1), heard(2, 5)), PodcastSettings.DELETE_HEARD_IMMEDIATELY, now))
    }

    @Test
    fun `immediately still waits as long as undo is offered`() {
        val justNow = HeardDownload(1, finishedAt = now - 10_000, downloadedAt = now - 10 * day)

        assertEquals(emptyList<Long>(), expiredHeardDownloads(listOf(justNow), PodcastSettings.DELETE_HEARD_IMMEDIATELY, now))
        assertEquals(listOf(1L), expiredHeardDownloads(listOf(justNow), PodcastSettings.DELETE_HEARD_IMMEDIATELY, now + HEARD_UNDO_GRACE_MS))
    }

    @Test
    fun `after n days only the old enough ones go`() {
        val list = listOf(heard(1, 1), heard(2, 3), heard(3, 4))

        assertEquals(listOf(2L, 3L), expiredHeardDownloads(list, 3, now))
    }

    @Test
    fun `an episode heard before it was downloaded stays until it is finished again`() {
        val downloadedLater = HeardDownload(1, finishedAt = now - 30 * day, downloadedAt = now - 2 * day)

        assertEquals(emptyList<Long>(), expiredHeardDownloads(listOf(downloadedLater), PodcastSettings.DELETE_HEARD_IMMEDIATELY, now))
        assertEquals(emptyList<Long>(), expiredHeardDownloads(listOf(downloadedLater), 3, now))
    }
}
