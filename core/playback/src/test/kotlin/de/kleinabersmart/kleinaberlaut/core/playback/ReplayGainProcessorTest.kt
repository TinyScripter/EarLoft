package de.kleinabersmart.kleinaberlaut.core.playback

import android.os.Bundle
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import de.kleinabersmart.kleinaberlaut.core.model.ReplayGain
import de.kleinabersmart.kleinaberlaut.core.model.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class ReplayGainProcessorTest {

    @Test
    fun `vorbis comments are read case-insensitively`() {
        val metadata = Metadata(
            VorbisComment("replaygain_track_gain", "-6.50 dB"),
            VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.988"),
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "+1,5 dB"),
        )
        assertEquals(ReplayGain(trackGainDb = -6.5f, trackPeak = 0.988f, albumGainDb = 1.5f), metadata.replayGain())
    }

    @Test
    fun `id3 txxx and mp4 freeform tags are read`() {
        val id3 = Metadata(TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("-3.00 dB")))
        val mp4 = Metadata(InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-4.25 dB"))
        assertEquals(ReplayGain(trackGainDb = -3f), id3.replayGain())
        assertEquals(ReplayGain(albumGainDb = -4.25f), mp4.replayGain())
    }

    @Test
    fun `opus r128 gain is converted, missing tags give null`() {
        assertEquals(ReplayGain(trackGainDb = -1f), Metadata(VorbisComment("R128_TRACK_GAIN", "-1536")).replayGain())
        assertNull(Metadata(VorbisComment("TITLE", "Mondlicht")).replayGain())
        assertNull(null.replayGain())
    }

    @Test
    fun `album order needs a neighbour of the same album in track order`() {
        val track2 = QueuePosition(albumId = 1, discNumber = 1, trackNumber = 2)
        assertTrue(playsAlbumInOrder(QueuePosition(1, 1, 1), track2, null))
        assertTrue(playsAlbumInOrder(null, track2, QueuePosition(1, 2, 1)))
        assertFalse(playsAlbumInOrder(QueuePosition(1, 1, 3), track2, QueuePosition(2, 1, 3)))
        assertFalse(playsAlbumInOrder(null, track2, null))
        assertFalse(playsAlbumInOrder(QueuePosition(null, 1, 1), QueuePosition(null, 1, 2), null))
    }

    @Test
    fun `gain switches when the processor is flushed with the next period`() {
        val queue = QueueTimeline(song(1, albumId = 7, track = 1), song(2, albumId = 7, track = 2))
        val processor = pcm16Processor()
        processor.onSourceResolved(1, ReplayGain(trackGainDb = -6.0206f, albumGainDb = -12.0412f))
        // Song 2 hat keine Werte in der Datenbank, aber Tags in der Datei.
        processor.onFormat(queue.getUidOfPeriod(1), Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "6 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.25")))

        processor.setSettings(ReplayGainMode.TRACK, preAmpDb = 0f)
        processor.flushTo(queue, 0)
        assertEquals(listOf<Short>(500, -500), processor.process(1000, -1000))

        processor.flushTo(queue, 1)
        assertEquals(listOf<Short>(1995, Short.MAX_VALUE), processor.process(1000, 20000))

        // AUTO: beide Titel desselben Albums in Reihenfolge → Album-Gain.
        processor.setSettings(ReplayGainMode.AUTO, preAmpDb = 0f)
        processor.flushTo(queue, 0)
        assertEquals(listOf<Short>(250), processor.process(1000))

        processor.shuffleEnabled = true
        processor.flushTo(queue, 0)
        assertEquals(listOf<Short>(500), processor.process(1000))

        processor.setSettings(ReplayGainMode.OFF, preAmpDb = 0f)
        assertEquals(listOf<Short>(1000), processor.process(1000))
    }

    @Test
    fun `flush without period keeps the gain, unsupported encodings are bypassed`() {
        val queue = QueueTimeline(song(1, albumId = 7, track = 1))
        val processor = pcm16Processor()
        processor.onSourceResolved(1, ReplayGain(trackGainDb = -6.0206f))
        processor.setSettings(ReplayGainMode.TRACK, preAmpDb = 0f)
        processor.flushTo(queue, 0)
        processor.flush(StreamMetadata.DEFAULT)
        assertEquals(listOf<Short>(500), processor.process(1000))

        val pcm24 = ReplayGainProcessor()
        assertEquals(AudioFormat.NOT_SET, pcm24.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_24BIT)))
        assertFalse(pcm24.isActive)
    }

    private fun pcm16Processor() = ReplayGainProcessor().apply {
        configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        flush(StreamMetadata.DEFAULT)
    }

    private fun ReplayGainProcessor.flushTo(timeline: Timeline, periodIndex: Int) {
        flush(StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(timeline.getUidOfPeriod(periodIndex)).build())
    }

    private fun song(songId: Long, albumId: Long, track: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.song(songId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTrackNumber(track)
                    .setDiscNumber(1)
                    .setExtras(Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
                    .build(),
            )
            .build()

    /** Ein Fenster und eine Periode je Titel, wie bei einer Playlist im Player. */
    private class QueueTimeline(private vararg val items: MediaItem) : Timeline() {
        override fun getWindowCount() = items.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                windowIndex, items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                true, false, null, 0, C.TIME_UNSET, windowIndex, windowIndex, 0,
            )
        override fun getPeriodCount() = items.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, "period-$periodIndex", periodIndex, C.TIME_UNSET, 0, AdPlaybackState.NONE, false)
        override fun getIndexOfPeriod(uid: Any): Int = items.indices.firstOrNull { uid == "period-$it" } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = "period-$periodIndex"
    }

    private fun ReplayGainProcessor.process(vararg samples: Short): List<Short> {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        queueInput(input)
        val output = getOutput()
        return List(output.remaining() / 2) { output.getShort() }
    }
}
