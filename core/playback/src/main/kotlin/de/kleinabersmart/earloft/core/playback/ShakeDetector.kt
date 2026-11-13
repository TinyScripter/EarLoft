package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Erkennt Schütteln aus Beschleunigungen (in g): mehrere kräftige Ausschläge kurz hintereinander. Umdrehen im Bett oder
 * Ablegen gibt höchstens einen; nach einem Treffer ist eine Weile Ruhe, damit ein Schütteln nicht zweimal zählt.
 */
internal class ShakeCounter {
    private val peaks = ArrayDeque<Long>()
    private var lastShakeMs = Long.MIN_VALUE / 2

    /** `true`, wenn mit dieser Messung ein Schütteln erkannt ist. */
    fun onSample(gForce: Float, timeMs: Long): Boolean {
        if (timeMs - lastShakeMs < COOLDOWN_MS) return false
        if (gForce < THRESHOLD_G) return false
        peaks.addLast(timeMs)
        while (peaks.isNotEmpty() && timeMs - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size < PEAKS) return false
        peaks.clear()
        lastShakeMs = timeMs
        return true
    }

    fun reset() {
        peaks.clear()
    }

    companion object {
        const val THRESHOLD_G = 2.2f
        const val PEAKS = 3
        const val WINDOW_MS = 700L
        const val COOLDOWN_MS = 2_000L
    }
}

/** Beschleunigungssensor für das Schütteln beim Sleep-Timer; angemeldet nur zwischen [start] und [stop]. */
internal class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val counter = ShakeCounter()
    private var listening = false

    fun start() {
        if (listening || sensors == null || accelerometer == null) return
        listening = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
        counter.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (counter.onSample(g, event.timestamp / NANOS_PER_MILLI)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
