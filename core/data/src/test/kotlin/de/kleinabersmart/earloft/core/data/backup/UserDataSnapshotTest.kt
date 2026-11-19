package de.kleinabersmart.earloft.core.data.backup

import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserDataSnapshotTest {

    private fun progress(finished: Boolean) = UserBookProgressEntity(
        serverId = "abs", remoteId = "li_1", positionMs = 60_000, durationMs = 60_000, finished = finished, speed = null,
        updatedAt = 1_000, dirty = false,
    )

    @Test
    fun `a restored finished book takes its last change as the time it was finished`() {
        // The backup knows no `finishedAt`; without it "Remove from Continue listening" would postpone deleting the downloads (review F39).
        assertEquals(1_000L, progress(finished = true).toMain(bookId = 7).finishedAt)
        assertNull(progress(finished = false).toMain(bookId = 7).finishedAt)
    }
}
