package com.tzvi.kickoff.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Kickoff mark, drawn rather than loaded, so every part of it can move.
 *
 * Three elements, and they are the same three everywhere the mark appears - launcher
 * icon, splash, onboarding, loader, settings: a dark green ring open at the top right, a
 * real black-and-white football in the middle of it, and a small white ball sitting in
 * the opening as if it had just been chipped over the line.
 *
 * The ring used to run between two greens and the centre used to be a flat green
 * pentagon-and-petals silhouette. Both were the mark describing a football rather than
 * being one; one solid green and an actual panelled ball is the sketch as drawn.
 */
private const val RING_RADIUS = 0.360f
private const val RING_STROKE = 0.118f

/** The opening faces the top right; angles are Android's y-down screen convention. */
private const val GAP_CENTER_DEG = -45f
private const val GAP_HALF_DEG = 34f
private const val ARC_START_DEG = GAP_CENTER_DEG - GAP_HALF_DEG
private const val ARC_SWEEP_DEG = -(360f - 2 * GAP_HALF_DEG)

private const val DOT_RADIUS = 0.094f
private const val DOT_ANGLE_DEG = -40f
private const val DOT_ORBIT = RING_RADIUS + 0.02f

/** The ball in the middle, sized to leave an even margin inside the ring. */
private const val BALL_RADIUS = 0.222f

/** One dark green, everywhere. */
val KickoffGreen = Color(0xFF0A7233)

/** Kept as aliases so older call sites still resolve; both are the one green now. */
val KickoffRingDark = KickoffGreen
val KickoffRingLight = KickoffGreen

/** The balls are white - properly white, not tinted. */
val KickoffBallWhite = Color(0xFFFFFFFF)

/** The panels, and the seam the pentagon sits in. */
private val KickoffBallBlack = Color(0xFF14181A)

/**
 * A hairline rim so a white ball on a near-white page still has an edge.
 *
 * Translucent black rather than a colour: it darkens the light backgrounds where it is
 * needed and disappears into the dark ones where it is not.
 */
private val KickoffBallRim = Color(0x33000000)

@Composable
fun KickoffLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    /** 0f draws nothing, 1f the finished mark. Drive this to animate the logo in. */
    progress: Float = 1f,
) {
    Canvas(modifier = modifier.size(size)) {
        val p = progress.coerceIn(0f, 1f)
        // The static composable tells the same story as the animation, compressed:
        // ball first, ring around it, loose ball last.
        drawCentreBall(scale = (p / 0.4f).coerceIn(0f, 1f))
        drawRing(sweepFraction = ((p - 0.25f) / 0.6f).coerceIn(0f, 1f))
        drawSatellite(scale = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f))
    }
}

/**
 * The mark arriving, in three beats.
 *
 * The football drops in from above and bounces to rest; the ring then draws itself from
 * the top edge of the opening round to the other side; and the moment it arrives, the
 * loose ball pops in over the gap. Squash on each bounce is what sells the weight -
 * a ball that lands without deforming reads as a sticker, not a ball.
 */
@Composable
fun AnimatedKickoffLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    onFinished: () -> Unit = {},
) {
    val drop = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Slower than feels necessary, on purpose: this plays once, on the first screen
        // anyone ever sees, and it is the only place the mark gets to explain itself.
        drop.animateTo(1f, tween(durationMillis = 900, easing = BounceOutEasing))
        ring.animateTo(1f, tween(durationMillis = 1_000, easing = ArcEasing))
        pop.animateTo(1f, tween(durationMillis = 420, easing = OvershootEasing))
        onFinished()
    }

    Canvas(modifier = modifier.size(size)) {
        val dim = this.size.minDimension
        if (drop.value > 0f) {
            // Falls from one radius above; squash tracks how recently it hit the floor.
            val y = (1f - drop.value) * -dim * 0.5f
            val squash = 1f - 0.22f * bounceImpact(drop.value)
            translate(top = y) {
                scale(scaleX = 2f - squash, scaleY = squash, pivot = center) {
                    drawCentreBall(scale = 1f)
                }
            }
        }
        drawRing(sweepFraction = ring.value)
        if (pop.value > 0f) drawSatellite(scale = pop.value)
    }
}

/**
 * The loading state: the same football turning inside the same green ring.
 *
 * It is deliberately the logo and not a spinner - at 18dp inside a button it still reads
 * as the mark, so a screen that is waiting looks like the app thinking rather than like a
 * borrowed widget.
 */
@Composable
fun KickoffLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /**
     * The ring's colour. Dark green everywhere except inside a filled button, where the
     * button is already that green and the ring would disappear into it.
     */
    ringColor: Color = KickoffGreen,
) {
    val transition = rememberInfiniteTransition(label = "kickoff-loader")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1_600, easing = LinearEasing)),
        label = "spin",
    )
    // The ring breathes round behind it so the whole mark is alive, not just the ball.
    val sweepHead by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2_400, easing = LinearEasing)),
        label = "sweep",
    )

    Canvas(modifier = modifier.size(size)) {
        drawRing(sweepFraction = 1f, alpha = 0.20f, color = ringColor)
        drawTail(headDeg = ARC_START_DEG + sweepHead, sweepDeg = 120f, color = ringColor)
        rotate(degrees = spin, pivot = center) {
            drawBall(radius = this.size.minDimension * BALL_RADIUS)
        }
    }
}

// ---- drawing --------------------------------------------------------------------------

/**
 * A recognisable football: white sphere, black pentagon at the pole, five black panels
 * around it. Filled shapes rather than outlines, because a 1px outline disappears the
 * moment the mark is drawn at button size.
 */
private fun DrawScope.drawBall(radius: Float) {
    if (radius <= 0f) return
    drawCircle(color = KickoffBallWhite, radius = radius, center = center)

    val pentagonRadius = radius * 0.40f
    val path = Path()
    PANEL_ANGLES.forEachIndexed { index, degrees ->
        val angle = Math.toRadians(degrees.toDouble())
        val x = center.x + pentagonRadius * cos(angle).toFloat()
        val y = center.y + pentagonRadius * sin(angle).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, KickoffBallBlack)

    // The five outer panels, tucked just inside the edge so the sphere keeps its rim.
    PANEL_ANGLES.forEach { degrees ->
        val angle = Math.toRadians((degrees + 36).toDouble())
        val cx = center.x + radius * 0.68f * cos(angle).toFloat()
        val cy = center.y + radius * 0.68f * sin(angle).toFloat()
        rotate(degrees = degrees + 36f + 90f, pivot = Offset(cx, cy)) {
            drawOval(
                color = KickoffBallBlack,
                topLeft = Offset(cx - radius * 0.30f, cy - radius * 0.17f),
                size = Size(radius * 0.60f, radius * 0.34f),
            )
        }
    }

    drawCircle(
        color = KickoffBallRim,
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.07f),
    )
}

/** Vertex up, so one panel always sits straight above the pentagon. */
private val PANEL_ANGLES = listOf(-90f, -18f, 54f, 126f, 198f)

/** The centre ball, growing from nothing when the mark animates in. */
private fun DrawScope.drawCentreBall(scale: Float) {
    if (scale <= 0f) return
    drawBall(radius = size.minDimension * BALL_RADIUS * scale)
}

/**
 * The ring: one stroke, one green.
 *
 * It was a sweep gradient between two greens, and at logo size the light end read as a
 * worn patch rather than as shading. A single dark green is what the sketch has and it
 * survives being scaled down to a 24dp notification glyph.
 */
private fun DrawScope.drawRing(
    sweepFraction: Float,
    alpha: Float = 1f,
    color: Color = KickoffGreen,
) {
    if (sweepFraction <= 0f) return
    val dim = size.minDimension
    val radius = dim * RING_RADIUS
    val strokeWidth = dim * RING_STROKE

    rotate(degrees = ARC_START_DEG, pivot = center) {
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = ARC_SWEEP_DEG * sweepFraction,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            alpha = alpha,
        )
    }
}

/** The comet head running round the loader's ring, fading as it trails. */
private fun DrawScope.drawTail(headDeg: Float, sweepDeg: Float, color: Color) {
    val dim = size.minDimension
    val radius = dim * RING_RADIUS
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val steps = 12
    for (i in 0 until steps) {
        val f = i / steps.toFloat()
        drawArc(
            color = color.copy(alpha = 1f - f),
            startAngle = headDeg - sweepDeg * f - sweepDeg / steps,
            sweepAngle = sweepDeg / steps + 0.5f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = dim * RING_STROKE, cap = StrokeCap.Round),
        )
    }
}

/** The loose white ball in the ring's opening. */
private fun DrawScope.drawSatellite(scale: Float) {
    if (scale <= 0f) return
    val dim = size.minDimension
    val angle = Math.toRadians(DOT_ANGLE_DEG.toDouble())
    val radius = dim * DOT_RADIUS * scale
    val at = Offset(
        x = center.x + dim * DOT_ORBIT * cos(angle).toFloat(),
        y = center.y + dim * DOT_ORBIT * sin(angle).toFloat(),
    )
    drawCircle(color = KickoffBallWhite, radius = radius, center = at)
    drawCircle(
        color = KickoffBallRim,
        radius = radius,
        center = at,
        style = Stroke(width = radius * 0.16f),
    )
}

// ---- easing ---------------------------------------------------------------------------

/** The classic bounce-out curve: three touches, each losing height. */
val BounceOutEasing = Easing { fraction ->
    val n1 = 7.5625f
    val d1 = 2.75f
    var t = fraction
    when {
        t < 1f / d1 -> n1 * t * t
        t < 2f / d1 -> { t -= 1.5f / d1; n1 * t * t + 0.75f }
        t < 2.5f / d1 -> { t -= 2.25f / d1; n1 * t * t + 0.9375f }
        else -> { t -= 2.625f / d1; n1 * t * t + 0.984375f }
    }
}

/** How hard the ball is hitting the ground right now, for the squash. */
private fun bounceImpact(fraction: Float): Float {
    val value = BounceOutEasing.transform(fraction)
    // Near 1f means near the floor; squash spikes at the touches and dies by the end.
    return ((value - 0.9f) / 0.1f).coerceIn(0f, 1f) * (1f - fraction) * abs(1f - fraction * 0.4f)
}

/** Fast out, gentle landing - the ring being drawn by a confident hand. */
val ArcEasing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction) * (1f - fraction)
}

/** A small overshoot for the pop: past full size, then settle. */
val OvershootEasing = Easing { fraction ->
    val tension = 2.2f
    val t = fraction - 1f
    t * t * ((tension + 1f) * t + tension) + 1f
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
