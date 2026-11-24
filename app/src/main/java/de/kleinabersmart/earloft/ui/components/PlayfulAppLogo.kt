package de.kleinabersmart.earloft.ui.components

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.LocalDarkTheme
import de.kleinabersmart.earloft.ui.components.PremiumCountdown.Step
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * [AppLogo] with a small easter egg. Every tap makes the emblem thump like a speaker cone, sends a ring outward and lets a
 * note rise from the chimney of the house. Tapping on counts down to the "premium version" ([PremiumCountdown]); the
 * unlock ends with a burst of rings and notes and one of the messages that EarLoft is open source and always was complete.
 * The messages go through the snackbar like all others, so TalkBack reads them too. With "Remove animations" only the
 * messages remain.
 */
@Composable
fun PlayfulAppLogo(modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val resources = LocalResources.current
    val snackbars = LocalSnackbars.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val countdown = remember { PremiumCountdown(UNLOCK_MESSAGES.size) }
    val thump = remember { Animatable(1f) }
    val effects = remember { mutableStateListOf<LogoEffect>() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    val accent = if (LocalDarkTheme.current) LOGO_ORANGE_ON_DARK else LOGO_ORANGE_ON_LIGHT
    val note = painterResource(DesignR.drawable.ic_music_note)

    // Runs only while something is to be drawn; the draw phase reads the frame time, nothing recomposes per frame.
    val running = effects.isNotEmpty()
    LaunchedEffect(running) {
        while (running && effects.isNotEmpty()) {
            withFrameNanos { now ->
                effects.forEach { if (it.start == UNSTARTED) it.start = now + it.delayNanos }
                effects.removeAll { now - it.start > it.durationNanos }
                frameNanos = now
            }
        }
    }

    val onTap: () -> Unit = {
        val step = countdown.tap(SystemClock.uptimeMillis())
        val unlocked = step is Step.Unlocked
        haptics.performHapticFeedback(if (unlocked) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentTick)
        if (ValueAnimator.areAnimatorsEnabled()) {
            scope.launch {
                thump.animateTo(if (unlocked) 1.2f else 1.12f, tween(70, easing = FastOutLinearInEasing))
                thump.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium))
            }
            effects += if (unlocked) finale() else tapEffects()
        }
        when (step) {
            Step.Quiet -> Unit
            is Step.Remaining ->
                snackbars.show(resources.getQuantityString(R.plurals.premium_countdown, step.taps, step.taps))
            is Step.Unlocked ->
                snackbars.show(resources.getString(UNLOCK_MESSAGES[step.variant]), SnackbarDuration.Long)
        }
    }

    AppLogo(
        contentScale = contentScale,
        modifier = modifier
            // No ripple: the thump is the feedback. TalkBack reads the logo with "double tap to activate".
            .clickable(interactionSource = null, indication = null, onClick = onTap)
            .drawWithContent {
                drawContent()
                val now = frameNanos
                effects.forEach { it.draw(this, now, accent, note) }
            }
            .graphicsLayer {
                scaleX = thump.value
                scaleY = thump.value
                transformOrigin = TransformOrigin(EMBLEM_X / LOGO_WIDTH, EMBLEM_Y / LOGO_HEIGHT)
            },
    )
}

/** A ring and a note leaning to a random side. */
private fun tapEffects(): List<LogoEffect> = listOf(
    LogoEffect.Ring(delayNanos = 0L),
    LogoEffect.Note(delayNanos = 40 * MS, drift = Random.nextFloat() * 0.3f - 0.15f, rise = 0.55f, noteSize = 0.24f),
)

/** Three rings one after another and a fan of notes. */
private fun finale(): List<LogoEffect> =
    List(3) { LogoEffect.Ring(delayNanos = it * 140 * MS) } +
        List(FINALE_NOTES) { i ->
            val spread = i / (FINALE_NOTES - 1f) * 2f - 1f
            LogoEffect.Note(
                delayNanos = (i * 60 + Random.nextInt(40)) * MS,
                drift = spread * 0.7f,
                rise = 0.45f + Random.nextFloat() * 0.12f,
                noteSize = 0.2f + Random.nextFloat() * 0.08f,
            )
        }

/**
 * Something drawn over the logo for a short time. [start] is set on the first frame after it was added (plus
 * [delayNanos]); positions are relative to the logo artwork, so they fit the small header and the large About page alike.
 */
private sealed class LogoEffect(val delayNanos: Long, val durationNanos: Long) {
    var start = UNSTARTED

    fun draw(scope: DrawScope, now: Long, color: Color, note: Painter) {
        if (start == UNSTARTED || now < start) return
        val progress = ((now - start).toFloat() / durationNanos).coerceIn(0f, 1f)
        scope.draw(progress, color, note)
    }

    protected abstract fun DrawScope.draw(progress: Float, color: Color, note: Painter)

    /** Sound wave leaving the emblem. Stays inside the header, which clips to its bounds. */
    class Ring(delayNanos: Long) : LogoEffect(delayNanos, 650 * MS) {
        override fun DrawScope.draw(progress: Float, color: Color, note: Painter) {
            val art = artwork()
            val eased = 1f - (1f - progress) * (1f - progress)
            val radius = EMBLEM_RADIUS * art.scale * (1f + 0.55f * eased)
            drawCircle(
                color = color,
                radius = radius,
                center = art.point(EMBLEM_X, EMBLEM_Y),
                alpha = 0.75f * (1f - progress),
                style = Stroke(width = EMBLEM_RADIUS * art.scale * (0.07f - 0.05f * eased)),
            )
        }
    }

    /**
     * Note rising from the chimney like smoke: it sways, leans by [drift] (of the logo height, negative = left), rises by
     * [rise] and fades out before reaching the top of the header. [noteSize] is also a share of the logo height.
     */
    class Note(delayNanos: Long, private val drift: Float, private val rise: Float, private val noteSize: Float) :
        LogoEffect(delayNanos, 1_100 * MS) {
        override fun DrawScope.draw(progress: Float, color: Color, note: Painter) {
            val art = artwork()
            val height = LOGO_HEIGHT * art.scale
            val side = noteSize * height * (0.6f + 0.4f * min(1f, progress * 3f))
            val sway = sin(progress * 2f * PI.toFloat() * 1.5f) * 0.04f * height
            val chimney = art.point(CHIMNEY_X, CHIMNEY_TOP)
            val center = Offset(chimney.x + drift * height * progress + sway, chimney.y - rise * height * progress)
            val alpha = min(1f, progress * 8f) * (if (progress < 0.5f) 1f else (1f - progress) * 2f)
            translate(center.x - side / 2f, center.y - side / 2f) {
                rotate(degrees = sway / height * 300f, pivot = Offset(side / 2f, side / 2f)) {
                    with(note) { draw(Size(side, side), alpha, ColorFilter.tint(color)) }
                }
            }
        }
    }
}

/** Where the artwork lies in the drawn bounds: scaled to fit and centered like [ContentScale.Fit]. */
private class Artwork(val scale: Float, val origin: Offset) {
    fun point(x: Float, y: Float) = Offset(origin.x + x * scale, origin.y + y * scale)
}

private fun DrawScope.artwork(): Artwork {
    val scale = min(size.width / LOGO_WIDTH, size.height / LOGO_HEIGHT)
    return Artwork(scale, Offset((size.width - LOGO_WIDTH * scale) / 2f, (size.height - LOGO_HEIGHT * scale) / 2f))
}

private val UNLOCK_MESSAGES = listOf(
    R.string.premium_unlocked_1,
    R.string.premium_unlocked_2,
    R.string.premium_unlocked_3,
    R.string.premium_unlocked_4,
    R.string.premium_unlocked_5,
)

private const val UNSTARTED = Long.MIN_VALUE
private const val MS = 1_000_000L
private const val FINALE_NOTES = 8

// Geometry of logo_wide / logo_wide_light in viewport units, after the group's translation (-85, -121).
private const val LOGO_WIDTH = 1514f
private const val LOGO_HEIGHT = 481f
private const val EMBLEM_X = 317.9f
private const val EMBLEM_Y = 241.5f
private const val EMBLEM_RADIUS = 225.5f
private const val CHIMNEY_X = 241.5f
private const val CHIMNEY_TOP = 161.4f

// The oranges of the two logo variants.
private val LOGO_ORANGE_ON_DARK = Color(0xFFFB9C1E)
private val LOGO_ORANGE_ON_LIGHT = Color(0xFFD96C00)
