package keinabersmart.kleinaberlaut.ui.player

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private const val TWO_PI = (2 * PI).toFloat()
private const val WAVE_PERIODS_PER_SECOND = 0.6f

/**
 * Fortschrittsbalken, dessen gespielter Teil eine wandernde Schallwelle ist; der Rest ist eine gerade Linie.
 * Die Welle läuft nur bei [active] und flacht bei Pause animiert ab. Ist systemweit „Animationen entfernen“
 * eingestellt, bleibt sie flach.
 *
 * [inset] hält den Balken von den Rändern fern, damit er zur Daumenposition eines umgebenden `Slider` passt.
 * [gap] ist ein optionaler Abstand zur Fortschrittsposition (Daumen); standardmäßig schließen Welle und Linie
 * bündig an. Die Welle läuft vor der Fortschrittsposition auf null aus, damit sie nicht über den Daumen ragt.
 */
@Composable
fun WavyProgressBar(
    progress: () -> Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    strokeWidth: Dp = 4.dp,
    wavelength: Dp = 32.dp,
    maxAmplitude: Dp = 3.dp,
    inset: Dp = 0.dp,
    gap: Dp = 0.dp,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val running = active && animationsEnabled
    val amplitudeFactor by animateFloatAsState(
        targetValue = when {
            !running -> 0f
            emphasized -> 1.4f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 400),
        label = "waveAmplitude",
    )
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + (now - last) / 1_000_000_000f * WAVE_PERIODS_PER_SECOND * TWO_PI) % TWO_PI
            last = now
        }
    }

    Canvas(modifier.fillMaxWidth().height(maxAmplitude * 2 * 1.4f + strokeWidth)) {
        val stroke = strokeWidth.toPx()
        val startX = inset.toPx() + stroke / 2
        val endX = size.width - inset.toPx() - stroke / 2
        if (endX <= startX) return@Canvas
        val centerY = size.height / 2
        val progressX = startX + progress().coerceIn(0f, 1f) * (endX - startX)
        val gapPx = gap.toPx()
        val waveEndX = progressX - gapPx
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

        if (progressX + gapPx < endX) {
            drawLine(trackColor, Offset(progressX + gapPx, centerY), Offset(endX, centerY), stroke, StrokeCap.Round)
        }
        if (waveEndX <= startX) return@Canvas

        val amplitude = maxAmplitude.toPx() * amplitudeFactor
        val waveLengthPx = wavelength.toPx()
        // Zum Daumen hin läuft die Welle über eine Wellenlänge auf null aus.
        val taperPx = minOf(waveLengthPx, waveEndX - startX)
        fun waveY(x: Float): Float {
            val taper = ((waveEndX - x) / taperPx).coerceIn(0f, 1f)
            return centerY + amplitude * taper * sin((x - startX) / waveLengthPx * TWO_PI - phase)
        }
        val path = Path().apply {
            moveTo(startX, waveY(startX))
            var x = startX
            while (x < waveEndX) {
                x = (x + 2f).coerceAtMost(waveEndX)
                lineTo(x, waveY(x))
            }
        }
        drawPath(path, color, style = style)
    }
}

@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
