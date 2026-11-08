package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.subsonic.NotSubsonicException
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SyncFailureTest {

    @Test
    fun `server errors keep code and message, auth errors become wrong credentials`() {
        assertEquals(
            SyncFailure.ServerError(SubsonicException.NOT_FOUND, "Song not found"),
            SyncFailure.of(SubsonicException(SubsonicException.NOT_FOUND, "Song not found")),
        )
        assertEquals(SyncFailure.WrongCredentials, SyncFailure.of(SubsonicException(SubsonicException.WRONG_CREDENTIALS, "x")))
    }

    @Test
    fun `network errors are unreachable, 404 and foreign answers are an unexpected server`() {
        assertEquals(SyncFailure.Unreachable("timeout"), SyncFailure.of(IOException("timeout")))
        assertEquals(SyncFailure.Unreachable("HTTP 502"), SyncFailure.of(HttpStatusException(502)))
        assertEquals(SyncFailure.UnexpectedServer, SyncFailure.of(HttpStatusException(404)))
        assertEquals(SyncFailure.UnexpectedServer, SyncFailure.of(NotSubsonicException("html")))
    }

    @Test
    fun `http 401 means wrong credentials`() {
        assertEquals(SyncFailure.WrongCredentials, SyncFailure.of(HttpStatusException(401)))
    }

    @Test
    fun `anything else keeps its message or class name`() {
        assertEquals(SyncFailure.Other("disk full"), SyncFailure.of(IllegalStateException("disk full")))
        assertEquals(SyncFailure.Other("IllegalStateException"), SyncFailure.of(IllegalStateException()))
    }
}
