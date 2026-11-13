package de.kleinabersmart.earloft.core.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class StreamPreloaderTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private val server = FakeServer(mapOf(uri(2) to ByteArray(3000) { it.toByte() }, uri(3) to ByteArray(2000)))
    private val player = FakePlayer()

    @Before
    fun setUp() {
        cache = SimpleCache(folder.newFolder("streams"), NoOpCacheEvictor(), StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))
    }

    @After
    fun tearDown() = cache.release()

    private fun preloader(scope: CoroutineScope, limit: Long = StreamPreloader.METERED_LIMIT_BYTES) = StreamPreloader(
        player,
        { CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { server }.createDataSourceForDownloading() },
        { songId ->
            when (songId) {
                // Song 1 liegt als Download vor, die anderen kommen vom Server.
                1L -> listOf(ResolvedPlayback(PlaybackLocation.LocalFile(Uri.parse("file:///music/1.flac")), null, null))
                else -> listOf(ResolvedPlayback(PlaybackLocation.Remote(uri(songId), "nav:$songId"), null, "nav"))
            }
        },
        isMetered = { false },
        scope = scope,
        ioDispatcher = Dispatchers.Unconfined,
        meteredLimitBytes = limit,
    )

    @Test
    fun `streams are cached completely, downloads are skipped`() = runTest {
        preloader(this).preload(listOf(1, 2, 3), limitBytes = null)

        assertEquals(listOf(uri(2), uri(3)), server.opened)
        assertEquals(3000L, cache.getCachedBytes("nav:2", 0, C.LENGTH_UNSET.toLong()))
        assertEquals(2000L, cache.getCachedBytes("nav:3", 0, C.LENGTH_UNSET.toLong()))

        // Schon im Cache: kein zweiter Abruf.
        preloader(this).preload(listOf(2), limitBytes = null)
        assertEquals(2, server.opened.size)
    }

    @Test
    fun `over mobile data only the start is cached`() = runTest {
        preloader(this).preload(listOf(2), limitBytes = 1000)

        assertEquals(1000L, cache.getCachedBytes("nav:2", 0, C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun `upcoming songs follow the queue and repeat`() {
        player.update(listOf("song:1", "song:2", "episode:7", "song:3"), current = 0)
        // Einträge ohne Song (Folgen) entfallen.
        assertEquals(listOf(2L), StreamPreloader.upcomingSongIds(player, 2))
        assertEquals(listOf(2L, 3L), StreamPreloader.upcomingSongIds(player, 3))

        player.update(listOf("song:1", "song:2", "song:3"), current = 2)
        assertEquals(emptyList<Long>(), StreamPreloader.upcomingSongIds(player, 2))
        player.update(listOf("song:1", "song:2", "song:3"), current = 2, repeatMode = Player.REPEAT_MODE_ALL)
        assertEquals(listOf(1L, 2L), StreamPreloader.upcomingSongIds(player, 2))
        // „Einen wiederholen“: vorgeladen wird, was beim Weiterspringen kommt.
        player.update(listOf("song:1", "song:2", "song:3"), current = 0, repeatMode = Player.REPEAT_MODE_ONE)
        assertEquals(listOf(2L), StreamPreloader.upcomingSongIds(player, 1))
    }

    private fun uri(songId: Long): Uri = Uri.parse("https://music.example/rest/stream?id=$songId")

    /** Liefert die Bytes je Adresse und merkt sich, was geöffnet wurde. */
    private class FakeServer(private val files: Map<Uri, ByteArray>) : DataSource {
        val opened = mutableListOf<Uri>()
        private var data = ByteArray(0)
        private var position = 0
        private var remaining = 0

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec.uri
            data = files.getValue(dataSpec.uri)
            position = dataSpec.position.toInt()
            val rest = data.size - position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) rest else minOf(dataSpec.length.toInt(), rest)
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(length, remaining)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            remaining -= count
            return count
        }

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var items: List<String> = emptyList()
        private var current = 0
        private var repeatMode = Player.REPEAT_MODE_OFF

        fun update(items: List<String>, current: Int, repeatMode: Int = Player.REPEAT_MODE_OFF) {
            this.items = items
            this.current = current
            this.repeatMode = repeatMode
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.mapIndexed { index, mediaId -> MediaItemData.Builder("$index").setMediaItem(MediaItem.Builder().setMediaId(mediaId).build()).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else current)
            .setRepeatMode(repeatMode)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .build()
    }
}
