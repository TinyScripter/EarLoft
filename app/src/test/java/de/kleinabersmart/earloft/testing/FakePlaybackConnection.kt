package de.kleinabersmart.earloft.testing

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlaybackConnectionBindings
import de.kleinabersmart.earloft.core.playback.PlayerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Wiedergabe ohne `MediaController`: Tests setzen [state] und [currentPositionMs] und lesen die Befehle in [commands]. */
@Singleton
class FakePlaybackConnection @Inject constructor() : PlaybackConnection {

    override val state = MutableStateFlow(PlayerState())
    override val positionJumps: Flow<Unit> = MutableSharedFlow()
    override var currentPositionMs: Long = 0
    override val sleepTimerRemainingMs: Flow<Long?> = flowOf(null)
    override val sleepTimerToEnd: Flow<Boolean> = flowOf(false)

    private val _commands = MutableStateFlow<List<Command>>(emptyList())

    /** Alle Befehle in der Reihenfolge ihres Eingangs. */
    val commands: StateFlow<List<Command>> = _commands

    /** Wartet, bis [count] Befehle eingegangen sind (`BookLauncher` startet Bücher im `ApplicationScope`). */
    suspend fun awaitCommands(count: Int = 1): List<Command> = awaitReal { _commands.first { it.size >= count } }

    private fun record(command: Command) {
        _commands.value += command
    }

    override fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean) =
        record(Command.Play(songs.map { it.id }, startIndex, shuffle))

    override fun playFromSearch(query: String) = record(Command.PlayFromSearch(query))

    override suspend fun playBook(bookId: Long, startPositionMs: Long?): PreparedBook? {
        record(Command.PlayBook(bookId, startPositionMs))
        return null
    }

    override fun resumeMusic() = record(Command.Simple("resumeMusic"))
    override fun skipBack() = record(Command.Simple("skipBack"))
    override fun skipForward() = record(Command.Simple("skipForward"))
    override fun seekToTrack(trackIndex: Int, positionMs: Long) = record(Command.SeekToTrack(trackIndex, positionMs))
    override fun playNext(songs: List<Song>) = record(Command.Simple("playNext"))
    override fun addToQueue(songs: List<Song>) = record(Command.Simple("addToQueue"))
    override fun replaceUpcoming(songs: List<Song>) = record(Command.ReplaceUpcoming(songs.map { it.id }))
    override fun togglePlayPause() = record(Command.Simple("togglePlayPause"))
    override fun pause() = record(Command.Simple("pause"))
    override fun next() = record(Command.Simple("next"))
    override fun previous() = record(Command.Simple("previous"))
    override fun seekTo(positionMs: Long) = record(Command.Simple("seekTo $positionMs"))
    override fun skipToQueueItem(index: Int) = record(Command.Simple("skipToQueueItem $index"))
    override fun removeQueueItem(index: Int) = record(Command.Simple("removeQueueItem $index"))
    override fun moveQueueItem(from: Int, to: Int) = record(Command.Simple("moveQueueItem $from $to"))
    override fun clearQueue() = record(Command.Simple("clearQueue"))
    override fun toggleShuffle() = record(Command.Simple("toggleShuffle"))
    override fun cycleRepeatMode() = record(Command.Simple("cycleRepeatMode"))
    override fun changeRemoteVolume(up: Boolean) = record(Command.Simple("changeRemoteVolume $up"))
    override fun startSleepTimer(minutes: Int) = record(Command.Simple("startSleepTimer $minutes"))
    override fun startSleepTimerToEnd() = record(Command.Simple("startSleepTimerToEnd"))
    override fun cancelSleepTimer() = record(Command.Simple("cancelSleepTimer"))

    sealed interface Command {
        data class Play(val songIds: List<Long>, val startIndex: Int, val shuffle: Boolean) : Command
        data class ReplaceUpcoming(val songIds: List<Long>) : Command
        data class PlayFromSearch(val query: String) : Command
        data class PlayBook(val bookId: Long, val startPositionMs: Long?) : Command
        data class SeekToTrack(val trackIndex: Int, val positionMs: Long) : Command

        /** Befehle, deren Argumente die Tests bisher nicht prüfen. */
        data class Simple(val name: String) : Command
    }
}

/** Ersetzt im Test-Graphen den echten `MediaController` durch [FakePlaybackConnection]. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PlaybackConnectionBindings::class])
object FakePlaybackModule {
    @Provides
    fun providePlaybackConnection(fake: FakePlaybackConnection): PlaybackConnection = fake
}
