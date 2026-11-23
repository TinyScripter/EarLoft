package de.kleinabersmart.earloft.core.playback

import android.app.Application
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What controllers may do on the session ([controllerCommands]) and where a replaced queue starts ([queueStart]). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionAccessTest {

    private val ownCommands = listOf(ACTION_TOGGLE_FAVORITE, ACTION_CHAPTER_PREVIOUS, ACTION_CHAPTER_NEXT, ACTION_VOICE_CHECK)

    @Test
    fun `a foreign app may control playback but neither browse nor replace the queue nor set favorites`() {
        val commands = controllerCommands(trusted = false)

        assertTrue(commands.player.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.player.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.player.contains(Player.COMMAND_SET_VOLUME))
        assertFalse(commands.player.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.player.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(commands.session.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
        assertFalse(commands.session.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
        ownCommands.forEach { assertFalse(it, commands.session.contains(SessionCommand(it, Bundle.EMPTY))) }
    }

    @Test
    fun `a trusted controller gets the library, the queue and the app's own commands`() {
        val commands = controllerCommands(trusted = true)

        assertTrue(commands.player.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(commands.player.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(commands.session.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
        assertTrue(commands.session.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
        ownCommands.forEach { assertTrue(it, commands.session.contains(SessionCommand(it, Bundle.EMPTY))) }
    }

    @Test
    fun `the requested title keeps its position when earlier ones are dropped`() {
        // Request of five titles, the second and fourth are not playable.
        assertEquals(QueueStart(1, 42_000), queueStart(kept = listOf(0, 2, 4), requestedStart = 2, startPositionMs = 42_000))
    }

    @Test
    fun `a dropped start title hands over to the next remaining one from the beginning`() {
        assertEquals(QueueStart(1, 0), queueStart(kept = listOf(0, 2, 4), requestedStart = 1, startPositionMs = 42_000))
    }

    @Test
    fun `if nothing follows the dropped start title the queue starts at its first title`() {
        assertEquals(QueueStart(0, 0), queueStart(kept = listOf(0, 1), requestedStart = 3, startPositionMs = 42_000))
    }

    @Test
    fun `without a requested start the player decides`() {
        assertEquals(QueueStart(C.INDEX_UNSET, 5_000), queueStart(kept = listOf(1, 2), requestedStart = C.INDEX_UNSET, startPositionMs = 5_000))
    }
}
