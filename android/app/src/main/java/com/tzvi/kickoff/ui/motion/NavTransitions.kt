package com.tzvi.kickoff.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

/**
 * Screen-to-screen motion.
 *
 * Forward navigation slides in from the trailing edge and the outgoing screen slides
 * only a fraction of the way out, so the two feel connected rather than swapped. The
 * fade is deliberately shorter than the slide: content that fades for the full travel
 * reads as sluggish even when the movement is fast.
 */
object NavTransitions {

    private const val OUTGOING_PARALLAX = 4

    val forwardEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            initialOffsetX = { it / 3 },
        ) + fadeIn(tween(Motion.Duration.SHORT))
    }

    val forwardExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            targetOffsetX = { -it / OUTGOING_PARALLAX },
        ) + fadeOut(tween(Motion.Duration.SHORT))
    }

    val backEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            initialOffsetX = { -it / OUTGOING_PARALLAX },
        ) + fadeIn(tween(Motion.Duration.SHORT))
    }

    val backExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            targetOffsetX = { it / 3 },
        ) + fadeOut(tween(Motion.Duration.SHORT))
    }

    /**
     * For destinations reached from a tapped element rather than from a hierarchy: the
     * screen grows out of where the element was instead of arriving from the side.
     * Used as the fallback when a shared-bounds transform is unavailable.
     */
    val expandEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            initialScale = 0.88f,
        ) + fadeIn(tween(Motion.Duration.MEDIUM))
    }

    val expandExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            animationSpec = tween(Motion.Duration.MEDIUM, easing = Motion.Easings.accelerate),
            targetScale = 0.92f,
        ) + fadeOut(tween(Motion.Duration.SHORT))
    }

    /** Onboarding advances vertically so it reads as a stack, not a hierarchy. */
    val onboardingEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInVertically(
            animationSpec = tween(Motion.Duration.LONG, easing = Motion.Easings.emphasised),
            initialOffsetY = { it / 4 },
        ) + fadeIn(tween(Motion.Duration.MEDIUM))
    }

    val onboardingExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutVertically(
            animationSpec = tween(Motion.Duration.MEDIUM, easing = Motion.Easings.accelerate),
            targetOffsetY = { -it / 6 },
        ) + fadeOut(tween(Motion.Duration.SHORT))
    }
}
