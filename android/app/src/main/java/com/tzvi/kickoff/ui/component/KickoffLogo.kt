package com.tzvi.kickoff.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Kickoff mark, drawn rather than loaded, so it can be animated.
 *
 * The proportions are the same ones baked into the vector drawables - the ring is a
 * progress arc with a tracker dot at its head, closing around a football panel - which
 * is why the launcher icon and this composable read as the same object.
 */
private const val RING_RADIUS_RATIO = 25f / 108f
private const val STROKE_RATIO = 9f / 108f
private const val PENTAGON_RATIO = 13.5f / 108f
private const val DOT_RATIO = 6f / 108f
private const val ARC_START_DEG = -48f
private const val ARC_SWEEP_DEG = 296f

@Composable
fun KickoffLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    panelColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    dotColor: Color = MaterialTheme.colorScheme.primaryContainer,
    /** 0f draws nothing, 1f the finished mark. Drive this to animate the logo in. */
    progress: Float = 1f,
) {
    Canvas(modifier = modifier.size(size)) {
        drawMark(progress.coerceIn(0f, 1f), ringColor, panelColor, dotColor)
    }
}

/**
 * The logo assembling itself: the ring sweeps round, the panel drops in behind it, and
 * the tracker dot lands last. Used for the launch screen and the onboarding hero.
 */
@Composable
fun AnimatedKickoffLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    panelColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    dotColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onFinished: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = Motion.Duration.EXTRA_LONG,
                easing = Motion.Easings.emphasised,
            ),
        )
        onFinished()
    }
    KickoffLogo(
        modifier = modifier,
        size = size,
        ringColor = ringColor,
        panelColor = panelColor,
        dotColor = dotColor,
        progress = progress.value,
    )
}

/**
 * The loading state, built from the same mark: the ring detaches into a short arc that
 * runs the circle while the panel stays put. It is the logo, still moving - not a
 * generic spinner borrowed from somewhere else.
 */
@Composable
fun KickoffLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    panelColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
) {
    val transition = rememberInfiniteTransition(label = "kickoff-loader")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = LinearEasing),
        ),
        label = "rotation",
    )
    // The arc breathing between a short and a long sweep is what stops a constant-speed
    // spinner from looking mechanical.
    val sweep by transition.animateFloat(
        initialValue = 40f,
        targetValue = 260f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = Motion.Easings.standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Box(modifier = modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val radius = this.size.minDimension * RING_RADIUS_RATIO / (1f - 2 * 0.02f)
            val stroke = this.size.minDimension * STROKE_RATIO
            val inset = this.size.minDimension / 2f - radius

            drawPentagon(this.size.minDimension * PENTAGON_RATIO, panelColor)
            drawArc(
                color = ringColor,
                startAngle = rotation,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawMark(
    progress: Float,
    ringColor: Color,
    panelColor: Color,
    dotColor: Color,
) {
    val dimension = size.minDimension
    val radius = dimension * RING_RADIUS_RATIO
    val stroke = dimension * STROKE_RATIO
    val inset = dimension / 2f - radius

    // The panel scales in over the first two thirds, so it is settled before the
    // tracker dot arrives and the eye has one thing to follow at a time.
    val panelScale = (progress / 0.66f).coerceIn(0f, 1f)
    if (panelScale > 0f) {
        drawPentagon(dimension * PENTAGON_RATIO * panelScale, panelColor)
    }

    if (progress > 0f) {
        drawArc(
            color = ringColor,
            startAngle = ARC_START_DEG,
            sweepAngle = ARC_SWEEP_DEG * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }

    // The dot only appears once the ring is essentially closed, then pops to full size.
    val dotProgress = ((progress - 0.82f) / 0.18f).coerceIn(0f, 1f)
    if (dotProgress > 0f) {
        val angle = Math.toRadians(ARC_START_DEG.toDouble())
        drawCircle(
            color = dotColor,
            radius = dimension * DOT_RATIO * dotProgress,
            center = Offset(
                x = center.x + radius * cos(angle).toFloat(),
                y = center.y + radius * sin(angle).toFloat(),
            ),
        )
    }
}

/** A regular pentagon with a vertex pointing up - the panel of a football. */
private fun DrawScope.drawPentagon(radius: Float, color: Color) {
    if (radius <= 0f) return
    val path = Path()
    listOf(-90f, -18f, 54f, 126f, 198f).forEachIndexed { index, degrees ->
        val angle = Math.toRadians(degrees.toDouble())
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/** Bounds of the drawn mark, for callers that need to align something to it. */
fun logoBounds(size: Float): Rect {
    val outer = size * (RING_RADIUS_RATIO + STROKE_RATIO / 2f)
    return Rect(
        left = size / 2f - outer,
        top = size / 2f - outer,
        right = size / 2f + outer,
        bottom = size / 2f + outer,
    )
}

@Preview
@Composable
private fun KickoffLogoPreview() {
    KickoffTheme { KickoffLogo(size = 96.dp) }
}

@Preview
@Composable
private fun KickoffLoaderPreview() {
    KickoffTheme { KickoffLoader(size = 64.dp) }
}
