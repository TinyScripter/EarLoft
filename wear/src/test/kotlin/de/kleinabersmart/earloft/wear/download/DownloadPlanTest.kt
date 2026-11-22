package de.kleinabersmart.earloft.wear.download

import de.kleinabersmart.earloft.core.database.model.WearCandidateRow
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanTest {

    private fun planned(songId: Long, bytes: Long) = PlannedDownload(songId, "nd:$songId", bytes, "wear:album:1")

    @Test
    fun `queues in order while it fits and counts the rest as missing`() {
        val plan = planWithinBudget(listOf(planned(1, 400), planned(2, 400), planned(3, 400), planned(4, 100)), budgetBytes = 1_000)

        assertEquals(listOf(1L, 2L), plan.toQueue.map { it.songId })
        // The small fourth track is not moved ahead: the order of the selections is kept.
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

        // Plenty is free, but 1.5 GB are already used: only 0.5 GB left until the limit.
        assertEquals(500_000_000, downloadBudget(settings, freeBytes = 10_000_000_000, doneBytes = 1_500_000_000, reservedBytes = 0))
        // The smaller value wins.
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

        // 200 s * 16,000 B/s = 3.2 MB, +3 %
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
        // Two episodes of one hour each fit into 120 MB, the third no longer does.
        assertEquals(listOf(1, 2), takeWithinBudget(listOf(1, 2, 3), 120_000_000) { estimateEpisodeBytes(3_600_000) })
    }

    @Test
    fun `after a full storage the planner runs again as soon as there is room`() {
        val step = 50L * 1024 * 1024
        assertEquals(false, shouldReplanForRoom(budget = -1, storageFull = true, missingForStorage = 0, previousBudget = -1, growthBytes = step))
        assertEquals(true, shouldReplanForRoom(budget = 1, storageFull = true, missingForStorage = 0, previousBudget = 0, growthBytes = step))
    }

    @Test
    fun `titles left out are planned again once the budget has grown noticeably`() {
        val step = 50L * 1024 * 1024
        fun replan(budget: Long, previous: Long?, missing: Int = 3) =
            shouldReplanForRoom(budget, storageFull = false, missingForStorage = missing, previousBudget = previous, growthBytes = step)

        assertEquals("first report of the process", true, replan(10, previous = null))
        assertEquals("shrinking while downloading", false, replan(200_000_000, previous = 210_000_000))
        assertEquals("a little more", false, replan(200_000_000, previous = 190_000_000))
        assertEquals("space freed", true, replan(300_000_000, previous = 200_000_000))
        assertEquals("nothing missing", false, replan(300_000_000, previous = 200_000_000, missing = 0))
        assertEquals("still no room", false, replan(0, previous = null))
    }

    @Test
    fun `space freed in small steps adds up until it is worth planning`() {
        val step = 50L * 1024 * 1024
        var reference: Long? = null
        var replans = 0
        // 10 MB freed per report, five reports: 50 MB in all.
        for (budget in listOf(0L, 10, 20, 30, 40, 50).map { it * 1024 * 1024 + 1 }) {
            val replan = shouldReplanForRoom(budget, storageFull = false, missingForStorage = 3, previousBudget = reference, growthBytes = step)
            if (replan) replans++
            reference = budgetReference(reference, budget, replan)
        }
        // The first report of the process plans, then once more after 50 MB.
        assertEquals(2, replans)
    }

    @Test
    fun `the reference follows a shrinking budget and resets when planning`() {
        assertEquals(100L, budgetReference(previousReference = null, budget = 100, replanned = false))
        assertEquals(80L, budgetReference(previousReference = 100, budget = 80, replanned = false))
        assertEquals(100L, budgetReference(previousReference = 100, budget = 130, replanned = false))
        assertEquals(130L, budgetReference(previousReference = 100, budget = 130, replanned = true))
    }
}
