package de.kleinabersmart.earloft.core.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SongDataSourceTest {

    private var now = 0L
    private val health = SourceHealth(clock = { now })
    private val upstream = FakeUpstream()
    private val resolved = mutableListOf<Pair<Long, ReplayGain?>>()
    private val playing = PlayingSources()

    private val home = stream("home", "home:1", ReplayGain(trackGainDb = -3f))
    private val office = stream("office", "office:1", ReplayGain(trackGainDb = -4f))

    private fun dataSource(vararg candidates: ResolvedPlayback) =
        SongDataSource(
            upstream,
            { candidates.toList() },
            health,
            onResolved = { songId, rg -> resolved += songId to rg },
            onOpened = playing::record,
        )

    @Test
    fun `unreachable server falls back to the next source`() {
        upstream.unreachable += "home"

        val length = dataSource(home, office).open(spec(songId = 1))

        assertEquals(100L, length)
        assertEquals(listOf("home", "office"), upstream.opened)
        assertEquals(listOf(1L to office.replayGain), resolved)
        assertEquals(PlayedFrom(PlayedFrom.Kind.STREAM, "office"), playing.bySong.value[1L])
    }

    @Test
    fun `recently failed server is tried last, later first again`() {
        upstream.unreachable += "home"
        dataSource(home, office).open(spec(songId = 1))
        upstream.unreachable.clear()
        upstream.opened.clear()

        dataSource(home, office).open(spec(songId = 2))
        assertEquals(listOf("office"), upstream.opened)

        now += 3 * 60 * 1000L
        upstream.opened.clear()
        dataSource(home, office).open(spec(songId = 3))
        assertEquals(listOf("home"), upstream.opened)
    }

    @Test
    fun `seeking stays on the source that delivered the start`() {
        upstream.unreachable += "home"
        dataSource(home, office).open(spec(songId = 1))
        upstream.unreachable.clear()
        now += 3 * 60 * 1000L
        upstream.opened.clear()

        dataSource(home, office).open(spec(songId = 1, position = 5000))
        assertEquals(listOf("office"), upstream.opened)

        // No switching in the middle of the file, even if the source fails.
        upstream.unreachable += "office"
        upstream.opened.clear()
        assertThrows(IOException::class.java) { dataSource(home, office).open(spec(songId = 1, position = 5000)) }
        assertEquals(listOf("office"), upstream.opened)
    }

    @Test
    fun `download is never marked unreachable, all failing throws`() {
        val download = ResolvedPlayback(PlaybackLocation.LocalFile(Uri.parse("file:///dl/1.flac")), null, sourceId = null)
        upstream.unreachable += listOf("home", "1.flac")

        assertThrows(IOException::class.java) { dataSource(download, home).open(spec(songId = 1)) }
        upstream.unreachable.clear()
        upstream.opened.clear()

        dataSource(download, home).open(spec(songId = 2))
        assertEquals(listOf("1.flac"), upstream.opened)
        assertEquals(PlayedFrom(PlayedFrom.Kind.DOWNLOAD, null), playing.bySong.value[2L])
    }

    @Test
    fun `local file counts as device, opening in the middle records nothing`() {
        val local = ResolvedPlayback(PlaybackLocation.LocalFile(Uri.parse("content://media/7")), null, sourceId = "local")
        dataSource(local, home).open(spec(songId = 3))
        assertEquals(PlayedFrom(PlayedFrom.Kind.DEVICE, "local"), playing.bySong.value[3L])

        // Only the start is reported: a jump into the middle of the file says nothing about where the title plays from.
        dataSource(home).open(spec(songId = 4, position = 5000))
        assertEquals(null, playing.bySong.value[4L])
    }

    @Test
    fun `over mobile data a cached original beats the conversion, seeking keeps the version`() {
        val opus = ResolvedPlayback(
            PlaybackLocation.Remote(Uri.parse("http://home-opus/stream"), "home:1:opus-128"), null, sourceId = "home", transcodedTo = "opus" to 128,
        )
        fun open(songId: Long, position: Long, cached: Set<String>, vararg candidates: ResolvedPlayback) =
            SongDataSource(upstream, { candidates.toList() }, health, onResolved = { _, _ -> }, onOpened = playing::record, isFullyCached = { it in cached })
                .open(spec(songId, position))

        // The original is already completely in the cache: costs nothing, so that one.
        open(1, 0, setOf("home:1"), opus, home)
        assertEquals(listOf("home"), upstream.opened)
        assertEquals(PlayedFrom(PlayedFrom.Kind.STREAM, "home"), playing.bySong.value[1L])

        upstream.opened.clear()
        open(2, 0, emptySet(), opus, home)
        assertEquals(listOf("home-opus"), upstream.opened)
        assertEquals(PlayedFrom(PlayedFrom.Kind.STREAM, "home", "opus" to 128), playing.bySong.value[2L])

        // Switched to Wi-Fi, the transcode is no longer a candidate: seeking still stays with it.
        upstream.opened.clear()
        open(2, 5000, emptySet(), home)
        assertEquals(listOf("home-opus"), upstream.opened)
    }

    @Test
    fun `cached originals move only ahead of conversions`() {
        val download = ResolvedPlayback(PlaybackLocation.LocalFile(Uri.parse("file:///dl/1.flac")), null, sourceId = null)
        val opus = home.copy(location = PlaybackLocation.Remote(Uri.parse("http://home-opus/stream"), "home:1:opus-128"), transcodedTo = "opus" to 128)
        assertEquals(listOf(download, office, opus, home), listOf(download, opus, home, office).preferCachedOriginals { it == "office:1" })
        // Without a transcode everything stays as it is.
        assertEquals(listOf(home, office), listOf(home, office).preferCachedOriginals { it == "office:1" })
    }

    private fun spec(songId: Long, position: Long = 0) =
        DataSpec.Builder().setUri(AppUris.song(songId)).setPosition(position).build()

    private fun stream(host: String, key: String, replayGain: ReplayGain) =
        ResolvedPlayback(PlaybackLocation.Remote(Uri.parse("http://$host/stream"), key), replayGain, sourceId = host)

    /** Opens everything except the hosts or file names in [unreachable]. */
    private class FakeUpstream : DataSource {
        val unreachable = mutableSetOf<String>()
        val opened = mutableListOf<String>()

        override fun open(dataSpec: DataSpec): Long {
            val name = dataSpec.uri.host?.takeIf { it.isNotEmpty() } ?: dataSpec.uri.lastPathSegment.orEmpty()
            opened += name
            if (name in unreachable) throw IOException("$name nicht erreichbar")
            return 100
        }

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
