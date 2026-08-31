package com.tzvi.kickoff.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * One motion vocabulary for the whole app.
 *
 * Material 3 splits motion in two: *spatial* movement is spring-based, because anything
 * that changes position or size should feel like it has mass, while *effects* like fade
 * and colour are duration-based, because a spring on opacity just reads as a flicker.
 * Every animation in the app picks from here rather than inventing its own numbers.
 */
object Motion {

    object Duration {
        const val SHORT = 150
        const val MEDIUM = 300
        const val LONG = 450
        const val EXTRA_LONG = 600
    }

    object Easings {
        /** Material's standard: decelerate quickly, settle gently. */
        val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
        val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
        /** Overshoots slightly - for a container opening, never for text. */
        val emphasised: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }

    /** Springs. Spatial values move on these; opacity and colour never do. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
    )

    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 700f,
    )

    /** Noticeably bouncy - reserved for the island opening and the score flip. */
    fun <T> spatialExpressive(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = 320f,
    )

    fun <T> effects(durationMillis: Int = Duration.MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = Easings.standard)

    // Typed variants: IntSize/IntOffset/Dp springs need their own visibility thresholds
    // or Compose settles them a whole pixel early and the last frame visibly snaps.
    fun sizeSpring(): FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
        visibilityThreshold = IntSize(1, 1),
    )

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
        visibilityThreshold = IntOffset(1, 1),
    )

    fun dpSpring(): FiniteAnimationSpec<Dp> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
        visibilityThreshold = Dp.VisibilityThreshold,
    )

    fun floatSpring(): FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
