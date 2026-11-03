package keinabersmart.kleinaberlaut.wear.download

import keinabersmart.kleinaberlaut.core.database.model.WearCandidateRow
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanTest {

    private fun planned(songId: Long, bytes: Long) = PlannedDownload(songId, "nd:$songId", bytes, "wear:album:1")

    @Test
    fun `queues in order while it fits and counts the rest as missing`() {
        val plan = planWithinBudget(listOf(planned(1, 400), planned(2, 400), planned(3, 400), planned(4, 100)), budgetBytes = 1_000)

        assertEquals(listOf(1L, 2L), plan.toQueue.map { it.songId })
        // Der kleine vierte Titel wird nicht vorgezogen: Die Reihenfolge der Auswahlen bleibt erhalten.
        assertEquals(2, plan.missing)
    }

    @Test
    fun `negative or zero budget queues nothing`() {
        assertEquals(3, planWithinBudget(listOf(planned(1, 10), planned(2, 10), planned(3, 10)), budgetBytes = -5).missing)
        assertEquals(0, planWithinBudget(emptyList(), budgetBytes = 0).missing)
    }

    @Test
    fun `everything fits exactly`() {
        val plan = planWithinBudget(listOf(planned(1, 500), planned(2, 500)), budgetBytes = 1_000)

        assertEquals(2, plan.toQueue.size)
        assertEquals(0, plan.missing)
    }

    @Test
    fun `budget keeps the minimum free space`() {
        val settings = WearSettingsDto(minFreeBytes = 1_000_000_000)

        assertEquals(500_000_000, downloadBudget(settings, freeBytes = 1_500_000_000, doneBytes = 0, reservedBytes = 0))
        assertEquals(400_000_000, downloadBudget(settings, freeBytes = 1_500_000_000, doneBytes = 0, reservedBytes = 100_000_000))
    }

    @Test
    fun `fixed limit counts finished downloads`() {
        val settings = WearSettingsDto(storageLimitBytes = 2_000_000_000, minFreeBytes = 0)

        // Frei ist reichlich, aber 1,5 GB sind schon belegt: nur noch 0,5 GB bis zur Grenze.
        assertEquals(500_000_000, downloadBudget(settings, freeBytes = 10_000_000_000, doneBytes = 1_500_000_000, reservedBytes = 0))
        // Der kleinere Wert gewinnt.
        assertEquals(100_000_000, downloadBudget(settings, freeBytes = 100_000_000, doneBytes = 0, reservedBytes = 0))
    }

    @Test
    fun `start check needs room for at least the smallest pending download`() {
        assertEquals(true, hasRoomForAny(emptyList(), budgetBytes = 0))
        assertEquals(true, hasRoomForAny(listOf(5_000, 800), budgetBytes = 1_000))
        assertEquals(false, hasRoomForAny(listOf(5_000, 1_200), budgetBytes = 1_000))
        assertEquals(false, hasRoomForAny(listOf(1), budgetBytes = -10))
    }

    @Test
    fun `opus estimate is duration times 128 kbit plus overhead`() {
        val candidate = WearCandidateRow("nd:1", 1, SourceType.SUBSONIC, 0, bitRate = 900, sizeBytes = 30_000_000, durationMs = 200_000)

        // 200 s * 16 000 B/s = 3,2 MB, +3 %
        assertEquals(3_296_000, estimateBytes(candidate, WearQuality.OPUS_128))
        assertEquals(30_000_000, estimateBytes(candidate, WearQuality.ORIGINAL))
    }

    @Test
    fun `phone files are never transcoded`() {
        val candidate = WearCandidateRow("phone:local:1", 1, SourceType.PHONE, 0, bitRate = 900, sizeBytes = 30_000_000, durationMs = 200_000)

        assertEquals(30_000_000, estimateBytes(candidate, WearQuality.OPUS_128))
    }

    @Test
    fun `original estimate falls back to the bit rate`() {
        val withBitRate = WearCandidateRow("nd:1", 1, SourceType.SUBSONIC, 0, bitRate = 256, sizeBytes = null, durationMs = 100_000)
        val unknown = WearCandidateRow("nd:2", 2, SourceType.SUBSONIC, 0, bitRate = null, sizeBytes = null, durationMs = 100_000)

        assertEquals(3_200_000, estimateBytes(withBitRate, WearQuality.ORIGINAL))
        assertEquals(4_000_000, estimateBytes(unknown, WearQuality.ORIGINAL))
    }

    @Test
    fun `phone transfer estimate rounds up and is at least one minute`() {
        assertEquals(1, estimatePhoneTransferMinutes(1))
        assertEquals(1, estimatePhoneTransferMinutes(6_144_000))
        assertEquals(2, estimatePhoneTransferMinutes(6_144_001))
        assertEquals(10, estimatePhoneTransferMinutes(60_000_000))
    }

    @Test
    fun `episodes are estimated at 128 kbit per second, an hour without duration`() {
        assertEquals(57_600_000, estimateEpisodeBytes(3_600_000))
        assertEquals(57_600_000, estimateEpisodeBytes(0))
        // Zwei Folgen von je einer Stunde passen in 120 MB, die dritte nicht mehr.
        assertEquals(listOf(1, 2), takeWithinBudget(listOf(1, 2, 3), 120_000_000) { estimateEpisodeBytes(3_600_000) })
    }
}
