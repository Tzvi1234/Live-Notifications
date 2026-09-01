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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
 * The design is the user's own sketch made precise: an open two-tone ring, a football -
 * pentagon plus five petals - in the middle, and a loose ball sitting in the ring's
 * opening at the top right, as if it had just been chipped over the line. The launcher
 * icon, the splash, the notification glyph and this composable all share these exact
 * proportions, so the mark reads as one object everywhere.
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

private const val PENTAGON_RADIUS = 0.125f
private const val PETAL_ORBIT = 0.205f
private const val PETAL_WIDTH = 0.165f
private const val PETAL_HEIGHT = 0.092f

/** The two greens the ring runs between, light at the top of the sweep. */
val KickoffRingLight = Color(0xFF35E36C)
val KickoffRingDark = Color(0xFF0C9A4A)

/** The ball's tint: not pure white, so it holds its shape against a white surface. */
val KickoffBallTint = Color(0xFFEAF5ED)

/** How many strokes the ring is built from; enough that the gradient reads as smooth. */
private const val RING_SEGMENTS = 60

@Composable
fun KickoffLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    ballColor: Color = ballColorFor(),
    /** 0f draws nothing, 1f the finished mark. Drive this to animate the logo in. */
    progress: Float = 1f,
) {
    Canvas(modifier = modifier.size(size)) {
        val p = progress.coerceIn(0f, 1f)
        // The static composable tells the same story as the animation, compressed:
        // ball first, ring around it, loose ball last.
        drawFootball(scale = (p / 0.4f).coerceIn(0f, 1f), color = ballColor)
        drawRing(sweepFraction = ((p - 0.25f) / 0.6f).coerceIn(0f, 1f))
        drawSatellite(scale = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f), color = ballColor)
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
    ballColor: Color = ballColorFor(),
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
                    drawFootball(scale = 1f, color = ballColor)
                }
            }
        }
        drawRing(sweepFraction = ring.value)
        if (pop.value > 0f) drawSatellite(scale = pop.value, color = ballColor)
    }
}

/**
 * The loading state: a proper black-and-white football turning inside the green ring.
 *
 * The previous one orbited a mint dot around the outside, which read as a spinner with a
 * ball glued to it. A ball that actually spins - panels sweeping round behind the seam -
 * is the thing itself moving, and it is legible at 18dp inside a button.
 */
@Composable
fun KickoffLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ringColor: Color = KickoffRingLight,
    panelColor: Color = KickoffRingDark,
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
        drawRing(sweepFraction = 1f, alpha = 0.22f)
        drawTail(headDeg = ARC_START_DEG + sweepHead, sweepDeg = 120f, color = ringColor)
        rotate(degrees = spin, pivot = center) {
            drawSpinningBall(radius = this.size.minDimension * BALL_RADIUS, seam = panelColor)
        }
    }
}

/**
 * A recognisable football: white sphere, black pentagon at the pole, five black panels
 * around it. Filled shapes rather than outlines, because a 1px outline disappears the
 * moment the loader is drawn at button size.
 */
private fun DrawScope.drawSpinningBall(radius: Float, seam: Color) {
    if (radius <= 0f) return
    drawCircle(color = KickoffBallWhite, radius = radius, center = center)

    val pentagonRadius = radius * 0.40f
    val path = Path()
    listOf(-90f, -18f, 54f, 126f, 198f).forEachIndexed { index, degrees ->
        val angle = Math.toRadians(degrees.toDouble())
        val x = center.x + pentagonRadius * cos(angle).toFloat()
        val y = center.y + pentagonRadius * sin(angle).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, seam)

    // The five outer panels, tucked just inside the edge so the sphere keeps its rim.
    listOf(-90f, -18f, 54f, 126f, 198f).forEach { degrees ->
        val angle = Math.toRadians((degrees + 36).toDouble())
        val cx = center.x + radius * 0.68f * cos(angle).toFloat()
        val cy = center.y + radius * 0.68f * sin(angle).toFloat()
        rotate(degrees = degrees + 36f + 90f, pivot = Offset(cx, cy)) {
            drawOval(
                color = seam,
                topLeft = Offset(cx - radius * 0.30f, cy - radius * 0.17f),
                size = Size(radius * 0.60f, radius * 0.34f),
            )
        }
    }
}

private const val BALL_RADIUS = 0.20f

/** The ball in the loader is a real football, so it is properly white. */
private val KickoffBallWhite = Color(0xFFFFFFFF)

/**
 * On a dark surface the ball is its natural mint white; on a light one it keeps a
 * whisper of green so it does not dissolve into the page.
 */
@Composable
private fun ballColorFor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        KickoffBallTint
    } else {
        Color(0xFFDFF0E4)
    }

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

// ---- drawing --------------------------------------------------------------------------

/**
 * One stroke with a sweep gradient, not sixty short ones.
 *
 * The segmented build drew visible seams at every joint - at logo size the ring read as
 * a striped barcode rather than a solid arc. A sweep gradient rotated so its seam falls
 * inside the ring's own opening gives the same two-tone run with nothing to see.
 */
private fun DrawScope.drawRing(sweepFraction: Float, alpha: Float = 1f) {
    if (sweepFraction <= 0f) return
    val dim = size.minDimension
    val radius = dim * RING_RADIUS
    val strokeWidth = dim * RING_STROKE

    // The gradient's own 0/360 seam is parked in the gap, where there is no stroke to
    // show it.
    //
    // The stop order looks backwards and is not. A sweep gradient runs clockwise from
    // fraction 0, while this arc is drawn counter-clockwise from its start - so the
    // arc's START sits at fraction 1 and its END at GRADIENT_END. Light therefore
    // belongs at 1 and dark at GRADIENT_END; the span below GRADIENT_END is the gap and
    // never gets painted.
    val brush = Brush.sweepGradient(
        0f to KickoffRingDark,
        GRADIENT_END to KickoffRingDark,
        1f to KickoffRingLight,
        center = center,
    )

    rotate(degrees = ARC_START_DEG, pivot = center) {
        drawArc(
            brush = brush,
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

/** Where the arc ends as a fraction of the full circle, for the gradient's dark stop. */
private val GRADIENT_END = (360f + ARC_SWEEP_DEG) / 360f

/** The comet tail behind the loader's orbiting ball, fading as it trails. */
private fun DrawScope.drawTail(headDeg: Float, sweepDeg: Float, color: Color) {
    val dim = size.minDimension
    val radius = dim * RING_RADIUS
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val steps = 12
    for (i in 0 until steps) {
        val f = i / steps.toFloat()
        drawArc(
            color = color.copy(alpha = 0.75f * (1f - f)),
            startAngle = headDeg - sweepDeg * f - sweepDeg / steps,
            sweepAngle = sweepDeg / steps + 0.5f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = dim * RING_STROKE * 0.55f, cap = StrokeCap.Round),
        )
    }
}

/** Pentagon plus five petals: the football, vertex up, one petal straight above. */
private fun DrawScope.drawFootball(scale: Float, color: Color) {
    if (scale <= 0f) return
    val dim = size.minDimension
    val pentagon = Path()
    listOf(-90f, -18f, 54f, 126f, 198f).forEachIndexed { index, degrees ->
        val angle = Math.toRadians(degrees.toDouble())
        val x = center.x + dim * PENTAGON_RADIUS * scale * cos(angle).toFloat()
        val y = center.y + dim * PENTAGON_RADIUS * scale * sin(angle).toFloat()
        if (index == 0) pentagon.moveTo(x, y) else pentagon.lineTo(x, y)
    }
    pentagon.close()
    drawPath(pentagon, color)

    listOf(-90f, -18f, 54f, 126f, 198f).forEach { degrees ->
        val angle = Math.toRadians(degrees.toDouble())
        val cx = center.x + dim * PETAL_ORBIT * scale * cos(angle).toFloat()
        val cy = center.y + dim * PETAL_ORBIT * scale * sin(angle).toFloat()
        rotate(degrees = degrees + 90f, pivot = Offset(cx, cy)) {
            drawOval(
                color = color,
                topLeft = Offset(
                    cx - dim * PETAL_WIDTH * scale / 2f,
                    cy - dim * PETAL_HEIGHT * scale / 2f,
                ),
                size = Size(dim * PETAL_WIDTH * scale, dim * PETAL_HEIGHT * scale),
            )
        }
    }
}

/** The loose ball in the ring's opening. */
private fun DrawScope.drawSatellite(scale: Float, color: Color) {
    if (scale <= 0f) return
    val dim = size.minDimension
    val angle = Math.toRadians(DOT_ANGLE_DEG.toDouble())
    drawCircle(
        color = color,
        radius = dim * DOT_RADIUS * scale,
        center = Offset(
            x = center.x + dim * DOT_ORBIT * cos(angle).toFloat(),
            y = center.y + dim * DOT_ORBIT * sin(angle).toFloat(),
        ),
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
