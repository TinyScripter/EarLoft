package de.kleinabersmart.earloft.core.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hint lifecycle in Android Auto ([PhoneActionPresenter]): resolution intent per hint (review5 P11) and clearing a stuck
 * `onSpokenNotPlayable` hint once the user signs in or grants the permission (review5 P12).
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhoneActionPresenterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a stuck sign-in hint from onSpokenNotPlayable clears once sign-in is restored`() {
        val sink = RecordingSink()
        val state = FakeState(unplayable = PhoneAction.SIGN_IN, stillBlocked = true)
        val presenter = presenter(sink, EmptyPlayer(), state)

        presenter.onSpokenNotPlayable(bookId = 7L)
        assertEquals(PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, sink.errorState?.errorCode)

        // Another server changed, but book 7's server is still signed out: hint stays.
        presenter.onCauseMayBeResolved()
        assertNotNull(sink.errorState)

        // Book 7's server is now signed in: hint clears even though the player never had an error.
        state.stillBlocked = false
        presenter.onCauseMayBeResolved()
        assertNull(sink.errorState)
    }

    @Test
    fun `a stuck home-network hint clears when the permission is granted or when Auto browses again`() {
        val sink = RecordingSink()
        val state = FakeState(unplayable = PhoneAction.HOME_NETWORK, stillBlocked = true)
        val presenter = presenter(sink, EmptyPlayer(), state)

        presenter.onSpokenNotPlayable(bookId = 9L)
        assertEquals(PlaybackException.ERROR_CODE_PERMISSION_DENIED, sink.errorState?.errorCode)

        // Grant via onCauseMayBeResolved (triggered by retryFailedStart -> PhoneActionResolutions).
        state.stillBlocked = false
        presenter.onCauseMayBeResolved()
        assertNull(sink.errorState)

        // Same hint shown again, then cleared on next root browse after the cause is gone.
        state.stillBlocked = true
        presenter.onSpokenNotPlayable(bookId = 9L)
        assertNotNull(sink.errorState)
        state.stillBlocked = false
        presenter.onRootBrowsed()
        assertNull(sink.errorState)
    }

    @Test
    fun `home network hints carry the dedicated request-home-network resolution intent`() {
        val sink = RecordingSink()
        val defaultResolution = PendingIntent.getActivity(context, 1, Intent("default"), PendingIntent.FLAG_IMMUTABLE)
        val homeResolution = PendingIntent.getActivity(
            context,
            2,
            Intent("home").putExtra(EXTRA_REQUEST_HOME_NETWORK, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val state = FakeState(unplayable = PhoneAction.HOME_NETWORK, stillBlocked = true)
        val presenter = PhoneActionPresenter(context, sink, EmptyPlayer(), state, TestScope(UnconfinedTestDispatcher())) { action ->
            if (action == PhoneAction.HOME_NETWORK) homeResolution else defaultResolution
        }

        presenter.onSpokenNotPlayable(bookId = 3L)
        @Suppress("DEPRECATION")
        val attached = sink.errorState?.extras?.getParcelable<PendingIntent>(
            MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
        )
        assertSame(homeResolution, attached)

        state.unplayable = PhoneAction.SIGN_IN
        presenter.onSpokenNotPlayable(bookId = 4L)
        @Suppress("DEPRECATION")
        val signInAttached = sink.errorState?.extras?.getParcelable<PendingIntent>(
            MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
        )
        assertSame(defaultResolution, signInAttached)
    }

    @Test
    fun `without a car the hint is only a message and the media card keeps its controls`() {
        val sink = RecordingSink(carConnected = false)
        val presenter = presenter(sink, EmptyPlayer(), FakeState(unplayable = PhoneAction.AUDIO_ACCESS, stillBlocked = true))

        presenter.onSpokenNotPlayable(bookId = 5L)

        assertNull(sink.errorState)
        assertEquals(1, sink.nonFatal.size)
    }

    @Test
    fun `a hint shown in the car goes when the car disconnects`() {
        val sink = RecordingSink()
        val presenter = presenter(sink, EmptyPlayer(), FakeState(unplayable = PhoneAction.SIGN_IN, stillBlocked = true))
        presenter.onSpokenNotPlayable(bookId = 5L)
        assertNotNull(sink.errorState)

        sink.carConnected = false
        presenter.onCarDisconnected()

        assertNull(sink.errorState)
    }

    private fun presenter(sink: RecordingSink, player: SimpleBasePlayer, state: FakeState) =
        PhoneActionPresenter(context, sink, player, state, TestScope(UnconfinedTestDispatcher())) { null }

    private class RecordingSink(override var carConnected: Boolean = true) : PhoneActionSink {
        var errorState: PlaybackException? = null
        val nonFatal = mutableListOf<String>()

        override fun sendNonFatalError(message: String, extras: Bundle) {
            nonFatal += message
        }

        override fun setErrorState(exception: PlaybackException?): Boolean {
            errorState = exception
            return true
        }
    }

    private class FakeState(
        var unplayable: PhoneAction? = null,
        var stillBlocked: Boolean = false,
        var setup: Boolean = false,
    ) : PhoneActionState {
        override suspend fun setupNeeded(): Boolean = setup
        override suspend fun forError(item: MediaItem?, error: PlaybackException): PhoneAction? = null
        override suspend fun forUnplayableBook(bookId: Long): PhoneAction? = unplayable
        override suspend fun isStillBlocked(action: PhoneAction, bookId: Long?): Boolean = stillBlocked
    }

    private class EmptyPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder().build()
    }
}
