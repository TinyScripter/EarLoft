package de.kleinabersmart.earloft.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NoSpaceTest {

    @Test
    fun `a full storage is recognised along the causes`() {
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isNoSpace())
        assertTrue(IOException("Channel closed", IOException("write failed: ENOSPC (No space left on device)")).isNoSpace())
    }

    @Test
    fun `a broken connection is no full storage`() {
        assertFalse(IOException("Phone not connected").isNoSpace())
        assertFalse(IOException(null as String?).isNoSpace())
    }
}
