package de.kleinabersmart.earloft.core.playback

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who counts as trusted ([isTrustedController]), what controllers may do on the session ([controllerCommands]) and where a
 * replaced queue starts ([queueStart]).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionAccessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = MediaSession.Builder(context, IdlePlayer()).setId("session-access-test").build()

    @After
    fun tearDown() {
        session.release()
    }

    private fun controller(
        packageName: String,
        verified: Boolean = true,
        legacy: Boolean = false,
        systemTrusted: Boolean = false,
        hints: Bundle = Bundle.EMPTY,
    ) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        packageName,
        /* pid= */ 1,
        /* uid= */ 10_123,
        if (legacy) MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION else MEDIA3_VERSION,
        if (legacy) MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION else MEDIA3_INTERFACE_VERSION,
        systemTrusted,
        hints,
        verified,
    )

    private fun trusted(controller: MediaSession.ControllerInfo) = session.isTrustedController(controller, context.packageName)

    @Test
    fun `the app itself, the system, its notification and the car are trusted`() {
        assertTrue(trusted(controller(context.packageName)))
        assertTrue(trusted(controller("com.example.watchcompanion", systemTrusted = true)))
        assertTrue(trusted(controller(context.packageName, hints = Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) })))
        assertTrue(trusted(controller(ANDROID_AUTO)))
        assertTrue(trusted(controller(AUTOMOTIVE_MEDIA)))
    }

    @Test
    fun `a foreign app is not trusted`() {
        assertFalse(trusted(controller("com.example.remote")))
        assertFalse(trusted(controller("com.example.remote", legacy = true)))
    }

    @Test
    fun `a name that could not be checked does not make a foreign app the app itself or the car`() {
        assertFalse(trusted(controller(context.packageName, verified = false)))
        assertFalse(trusted(controller(ANDROID_AUTO, verified = false)))
        assertFalse(trusted(controller(AUTOMOTIVE_MEDIA, verified = false)))
        // The system's own trust doesn't depend on the name.
        assertTrue(trusted(controller("com.example.watchcompanion", verified = false, systemTrusted = true)))
    }

    @Test
    fun `older controllers come through the platform and keep their name`() {
        // Android Auto connects as an older controller; before Android 13 Media3 cannot mark its name as checked.
        assertTrue(trusted(controller(ANDROID_AUTO, verified = false, legacy = true)))
        assertTrue(trusted(controller(context.packageName, verified = false, legacy = true)))
    }

    private val ownCommands = listOf(ACTION_TOGGLE_FAVORITE, ACTION_CHAPTER_PREVIOUS, ACTION_CHAPTER_NEXT, ACTION_PLAY_FROM_SEARCH)

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

    private class IdlePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).build()
    }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        const val AUTOMOTIVE_MEDIA = "com.android.car.media"
        const val MEDIA3_VERSION = 1_011_001_300
        const val MEDIA3_INTERFACE_VERSION = 7
    }
}
