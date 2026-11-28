package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities

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
    fun `a decoder or parsing error does not ask to sign in`() {
        // review5 P9: SIGN_IN only for IO errors, not for a broken or undecodable file.
        assertNull(
            phoneActionFor(
                error(PlaybackException.ERROR_CODE_DECODING_FAILED),
                signedOut = true,
                homeNetworkBlocked = true,
                audioAccessMissing = true,
            ),
        )
        assertNull(
            phoneActionFor(
                error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
                signedOut = true,
                homeNetworkBlocked = false,
                audioAccessMissing = false,
            ),
        )
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
    fun `home network is blocked only for a home address without permission and without VPN`() {
        // review5 P9 (5.5 A): a public episode or a Tailscale/VPN connection is never blocked by Android 17's home network permission.
        assertTrue(isHomeNetworkBlocked(listOf("http://192.168.0.140:8096/stream"), permissionMissing = true, vpnActive = false))
        assertFalse(isHomeNetworkBlocked(listOf("https://cdn.example.com/episode.mp3"), permissionMissing = true, vpnActive = false))
        assertFalse(isHomeNetworkBlocked(listOf("http://100.101.102.103:13378/file"), permissionMissing = true, vpnActive = true))
        assertFalse(isHomeNetworkBlocked(listOf("http://192.168.0.140:8096/stream"), permissionMissing = true, vpnActive = true))
        assertFalse(isHomeNetworkBlocked(listOf("http://192.168.0.140:8096/stream"), permissionMissing = false, vpnActive = false))
        assertFalse(isHomeNetworkBlocked(emptyList(), permissionMissing = true, vpnActive = false))
    }

    @Test
    fun `downloaded and content URIs count as local media`() {
        assertTrue(isLocalMediaUri(Uri.parse("file:///data/user/0/book.m4b")))
        assertTrue(isLocalMediaUri(Uri.parse("content://media/external/audio/1")))
        assertFalse(isLocalMediaUri(Uri.parse("http://192.168.0.140:13378/api/items/1/file/2")))
        assertFalse(isLocalMediaUri(null))
    }

    @Test
    fun `hasActiveVpn detects a VPN transport on the active network`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork!!

        val wifi = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(manager).setNetworkCapabilities(network, wifi)
        assertFalse(hasActiveVpn(context))

        val vpn = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(vpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        Shadows.shadowOf(manager).setNetworkCapabilities(network, vpn)
        assertTrue(hasActiveVpn(context))
    }

    @Test
    fun `setup is needed only if no enabled area has anything`() {
        assertTrue(setupNeeded(listOf(MUSIC, PODCASTS, BOOKS), hasMusic = false, hasPodcasts = false, hasBooks = false))
        assertFalse(setupNeeded(listOf(MUSIC, PODCASTS, BOOKS), hasMusic = false, hasPodcasts = true, hasBooks = false))
        // Books exist, but the area is off: the car shows nothing of them.
        assertTrue(setupNeeded(listOf(MUSIC), hasMusic = false, hasPodcasts = false, hasBooks = true))
    }

    @Test
    fun `the error state is only set while there is nothing the car could control`() {
        // Nothing loaded (setup hint) or the player failed (playback error): the error state replaces nothing.
        assertTrue(hintAsErrorState(mediaItemCount = 0, playerError = null))
        assertTrue(hintAsErrorState(mediaItemCount = 3, playerError = error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        // A book failed while music is loaded (playing or paused): only a message, play/pause and skip stay.
        assertFalse(hintAsErrorState(mediaItemCount = 3, playerError = null))
    }
}
