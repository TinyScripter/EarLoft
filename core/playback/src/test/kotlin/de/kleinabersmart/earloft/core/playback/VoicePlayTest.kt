package de.kleinabersmart.earloft.core.playback

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** A voice request that finds something it can't start right now says so, not "nothing found" (review5 8.12). */
class VoicePlayTest {

    @Test
    fun `the session's answer tells found but not playable from nothing found`() {
        assertEquals(VoicePlay.PLAYING, voicePlayOf(SessionResult.RESULT_SUCCESS))
        assertEquals(VoicePlay.NOT_PLAYABLE, voicePlayOf(SessionError.ERROR_INVALID_STATE))
        assertEquals(VoicePlay.NOTHING_FOUND, voicePlayOf(SessionError.ERROR_BAD_VALUE))
        assertEquals(VoicePlay.NOTHING_FOUND, voicePlayOf(SessionError.ERROR_NOT_SUPPORTED))
    }
}
