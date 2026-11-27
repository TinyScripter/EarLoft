package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.PlaybackException
import de.kleinabersmart.earloft.core.model.SpokenMode.BOOKS
import de.kleinabersmart.earloft.core.model.SpokenMode.MUSIC
import de.kleinabersmart.earloft.core.model.SpokenMode.PODCASTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which hint Android Auto gets when only the phone can help (car app quality VI-1). */
@RunWith(RobolectricTestRunner::class)
class PhoneActionHintsTest {

    private fun error(code: Int) = PlaybackException("failed", null, code)

    @Test
    fun `an expired sign-in wins over everything else`() {
        val action = phoneActionFor(
            error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            signedOut = true,
            homeNetworkBlocked = true,
            audioAccessMissing = false,
        )
        assertEquals(PhoneAction.SIGN_IN, action)
    }

    @Test
    fun `a timeout without the home network permission asks for it`() {
        val action = phoneActionFor(
            error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            signedOut = false,
            homeNetworkBlocked = true,
            audioAccessMissing = false,
        )
        assertEquals(PhoneAction.HOME_NETWORK, action)
    }

    @Test
    fun `a device file without audio permission asks for that, not for the network`() {
        val action = phoneActionFor(
            error(PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
            signedOut = false,
            homeNetworkBlocked = true,
            audioAccessMissing = true,
        )
        assertEquals(PhoneAction.AUDIO_ACCESS, action)
    }

    @Test
    fun `errors the phone cannot fix stay as they are`() {
        // A broken file is no missing permission.
        assertNull(phoneActionFor(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED), false, true, true))
        // Network error, but nothing missing.
        assertNull(phoneActionFor(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED), false, false, false))
    }

    @Test
    fun `setup is needed only if no enabled area has anything`() {
        assertTrue(setupNeeded(listOf(MUSIC, PODCASTS, BOOKS), hasMusic = false, hasPodcasts = false, hasBooks = false))
        assertFalse(setupNeeded(listOf(MUSIC, PODCASTS, BOOKS), hasMusic = false, hasPodcasts = true, hasBooks = false))
        // Books exist, but the area is off: the car shows nothing of them.
        assertTrue(setupNeeded(listOf(MUSIC), hasMusic = false, hasPodcasts = false, hasBooks = true))
    }
}
