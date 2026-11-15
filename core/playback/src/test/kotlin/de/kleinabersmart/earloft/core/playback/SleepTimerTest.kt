package de.kleinabersmart.earloft.core.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val scope = TestScope()

    /** Ein Buch, das mit der Testuhr läuft: Position = Start + vergangene Zeit × Tempo, solange es spielt. */
    private inner class FakeTarget : SleepTimerTarget {
        override var volume = 1f
        var pauseCount = 0
        var playCount = 0
        var rewound = 0L
        var spoken = true
        var bookId = 1L
        var ends = listOf(60_000L, 120_000L, 300_000L)
        var speed = 1f
        private var positionAtMs = 0L
        private var since = 0L
        private var playing = true

        var position: Long
            get() = positionAtMs + if (playing) ((scope.currentTime - since) * speed).toLong() else 0L
            set(value) {
                positionAtMs = value
                since = scope.currentTime
            }

        override val isPlaying: Boolean get() = playing
        override val isSpoken: Boolean get() = spoken

        override fun pause() {
            position = position
            playing = false
            pauseCount++
        }

        override fun play() {
            position = position
            playing = true
            playCount++
        }

        override fun spokenSpot(): SpokenSpot? = if (spoken) SpokenSpot(bookId, position, speed, ends) else null

        override fun rewind(heardMs: Long) {
            rewound += heardMs
            position = (position - (heardMs * speed).toLong()).coerceAtLeast(0)
        }
    }

    private val target = FakeTarget()
    private val timer = SleepTimer(now = { scope.currentTime }, scope = scope).apply { attach(target) }

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `fadeVolume sinkt linear auf null`() {
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS), 0.001f)
        assertEquals(0.5f, fadeVolume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, fadeVolume(0), 0.001f)
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS * 3), 0.001f)
    }

    @Test
    fun `pausiert nach Ablauf und stellt die Lautstaerke zurueck`() {
        timer.start(60_000)
        scope.advanceTimeBy(59_999)
        assertEquals(0, target.pauseCount)
        assertTrue(timer.state.value != null)
        advance(2)
        assertEquals(1, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.state.value)
    }

    @Test
    fun `blendet in den letzten Sekunden aus`() {
        timer.start(60_000)
        scope.advanceTimeBy(30_000)
        assertEquals(1f, target.volume, 0f)
        advance(20_000)
        // 10 s vor Ablauf: halbe Lautstärke.
        assertEquals(0.5f, target.volume, 0.02f)
    }

    @Test
    fun `Abbrechen verhindert die Pause und setzt die Lautstaerke zurueck`() {
        timer.start(60_000)
        scope.advanceTimeBy(50_000)
        timer.cancel()
        advance(60_000)
        assertEquals(0, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.state.value)
    }

    @Test
    fun `neuer Start ersetzt den laufenden Timer`() {
        timer.start(30_000)
        scope.advanceTimeBy(20_000)
        timer.start(60_000)
        advance(40_000)
        assertEquals(0, target.pauseCount)
        advance(30_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `ohne Player passiert nichts`() {
        timer.detach()
        timer.start(60_000)
        assertNull(timer.state.value)
    }

    @Test
    fun `bis zum Kapitelende haelt knapp vor der Grenze an`() {
        target.position = 10_000
        timer.startToEnd()
        scope.runCurrent()
        assertEquals(SleepTimerState(50_000, toEnd = true), timer.state.value)
        advance(49_000)
        assertEquals(0, target.pauseCount)
        advance(1_000)
        assertEquals(1, target.pauseCount)
        // Noch im Kapitel, nicht schon im nächsten.
        assertTrue(target.position in 59_000..59_999)
        assertNull(timer.state.value)
    }

    @Test
    fun `bis zum Kapitelende rechnet mit dem Tempo`() {
        target.speed = 2f
        target.position = 0
        timer.startToEnd()
        scope.runCurrent()
        assertEquals(30_000L, timer.state.value?.remainingMs)
        advance(30_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `Spulen in ein anderes Kapitel schlaeft an dessen Ende ein`() {
        target.position = 10_000
        timer.startToEnd()
        advance(5_000)
        // Ins dritte Kapitel (120 s bis 300 s).
        target.position = 200_000
        advance(1_000)
        assertEquals(99_000L, timer.state.value?.remainingMs)
        // Zurück ins erste: wieder dessen Ende.
        target.position = 1_000
        advance(1_000)
        assertEquals(58_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `ein anderes Buch bringt den Anfang seines Kapitels mit statt den des vorigen`() {
        target.position = 10_000
        timer.startToEnd()
        advance(1_000)
        // Ein anderes Buch, mitten im dritten Kapitel (120 s bis 300 s), ohne die Grenze zu überlaufen.
        target.bookId = 2
        target.position = 130_000
        advance(1_000)
        assertEquals(169_000L, timer.state.value?.remainingMs)
        // Zurück ins erste Kapitel dieses Buchs: Dessen Ende gilt, nicht das des dritten.
        target.position = 50_000
        advance(1_000)
        assertEquals(9_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `pausiert laeuft bis zum Ende nicht weiter und blendet nicht aus`() {
        target.position = 50_000
        timer.startToEnd()
        advance(1_000)
        target.pause()
        val paused = timer.state.value?.remainingMs
        advance(30_000)
        assertEquals(paused, timer.state.value?.remainingMs)
        assertEquals(1f, target.volume, 0f)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `Folge bis zum Ende und Musik beendet den Timer ohne Pause`() {
        target.ends = listOf(90_000)
        target.position = 0
        timer.startToEnd()
        advance(10_000)
        assertEquals(80_000L, timer.state.value?.remainingMs)
        target.spoken = false
        advance(2_000)
        assertNull(timer.state.value)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `ueber die Grenze ins naechste Item gilt als Ende`() {
        target.ends = listOf(10_000)
        target.position = 9_000
        timer.startToEnd()
        scope.runCurrent()
        // Die nächste Folge ist schon geladen, bevor der Timer wieder nachsieht.
        target.bookId = 2
        target.position = 0
        advance(1_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `Schuetteln nur kurz vor Ablauf`() {
        timer.start(10 * 60_000L)
        advance(60_000)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        advance(8 * 60_000L + 30_000)
        assertTrue(timer.shakeWindow.value)
    }

    @Test
    fun `pausiert kurz vor dem Kapitelende hilft Schuetteln nicht und der Sensor ruht`() {
        target.position = 20_000
        timer.startToEnd()
        advance(5_000)
        assertTrue(timer.shakeWindow.value)
        target.pause()
        advance(1_000)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        assertEquals(0L, target.rewound)
        // Wieder abgespielt: Das Fenster ist wieder offen.
        target.play()
        advance(1_000)
        assertTrue(timer.shakeWindow.value)
    }

    @Test
    fun `Schuetteln vor dem Ausblenden spult nicht zurueck`() {
        target.position = 100_000
        timer.start(5 * 60_000L)
        advance(5 * 60_000L - 40_000)
        assertEquals(1f, target.volume, 0f)
        assertTrue(timer.extend())
        assertEquals(0L, target.rewound)
        assertEquals(100_000L + 5 * 60_000L - 40_000, target.position)
    }

    @Test
    fun `Schuetteln beim Ausblenden setzt die Dauer neu und spult das Leise zurueck`() {
        target.position = 100_000
        timer.start(5 * 60_000L)
        advance(5 * 60_000L - 5_000)
        assertTrue(target.volume < 1f)
        assertTrue(timer.extend())
        assertEquals(15_000L, target.rewound)
        assertEquals(1f, target.volume, 0f)
        scope.runCurrent()
        assertEquals(5 * 60_000L, timer.state.value?.remainingMs)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `Schuetteln nach dem Einschlafen spielt weiter und spult zurueck`() {
        timer.start(60_000)
        advance(60_000)
        assertEquals(1, target.pauseCount)
        advance(60_000)
        assertTrue(timer.extend())
        assertEquals(1, target.playCount)
        assertEquals(SleepTimer.FADE_MS, target.rewound)
        scope.runCurrent()
        assertEquals(60_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `nach zwei Minuten hilft Schuetteln nicht mehr`() {
        timer.start(60_000)
        advance(60_000)
        advance(SleepTimer.SHAKE_AFTER_MS)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        assertEquals(0, target.playCount)
    }

    @Test
    fun `Schuetteln bis Kapitelende reicht bis zum Ende des naechsten Kapitels`() {
        target.position = 30_000
        timer.startToEnd()
        advance(30_000)
        assertEquals(1, target.pauseCount)
        assertTrue(timer.extend())
        scope.runCurrent()
        // 20 s zurück und dann bis 120 s.
        val remaining = timer.state.value?.remainingMs ?: 0
        assertTrue("$remaining", remaining in 79_000..81_000)
    }

    @Test
    fun `Schuetteln im letzten Abschnitt verlaengert um die zuletzt gewaehlte Dauer`() {
        timer.start(30 * 60_000L)
        timer.cancel()
        target.ends = listOf(90_000)
        target.position = 60_000
        timer.startToEnd()
        advance(30_000)
        assertEquals(1, target.pauseCount)
        assertTrue(timer.extend())
        scope.runCurrent()
        assertEquals(SleepTimerState(30 * 60_000L, toEnd = false), timer.state.value)
    }
}
