package com.tzvi.kickoff.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape

/**
 * Container transform: the tapped thing *becomes* the screen.
 *
 * A card, a settings row or a team chip is given a key here, and the destination gives
 * its own container the same key. Compose then interpolates the bounds of one into the
 * other, so opening a match reads as that match card growing to fill the screen rather
 * than as a new page arriving from somewhere else.
 *
 * Both scopes are published through composition locals so that a leaf composable can opt
 * into the transform without every layer between it and the NavHost having to pass the
 * scopes down.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Keys are strings so a route and the element that opened it can agree without a shared type. */
object TransformKeys {
    fun matchCard(matchId: Long) = "match-card-$matchId"
    fun matchScore(matchId: Long) = "match-score-$matchId"
    fun teamCrest(teamId: Int) = "team-crest-$teamId"
    fun teamCard(teamId: Int) = "team-card-$teamId"
    const val SETTINGS = "settings-container"
    const val CALENDAR = "calendar-container"
    const val ISLAND = "dynamic-island"
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val containerBounds = BoundsTransform { _: Rect, _: Rect ->
    // Bounds are spatial, so they get a spring; the cross-fade underneath is a tween.
    spring(dampingRatio = 0.9f, stiffness = 380f, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * Marks this composable as one half of a container transform.
 *
 * Silently does nothing when either scope is absent - a preview, a test, or a screen
 * rendered outside the NavHost still lays out normally instead of crashing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.containerTransform(
    key: String,
    clipShape: Shape = RectangleShape,
    enabled: Boolean = true,
): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    val animatedScope = LocalNavAnimatedScope.current
    if (!enabled || sharedScope == null || animatedScope == null) {
        this
    } else {
        with(sharedScope) {
            this@composed.sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = animatedScope,
                boundsTransform = containerBounds,
                enter = fadeIn(tween(Motion.Duration.MEDIUM)),
                exit = fadeOut(tween(Motion.Duration.SHORT)),
                clipInOverlayDuringTransition = OverlayClip(clipShape),
            )
        }
    }
}

/**
 * For a single element that exists on both sides - a crest, a scoreline - rather than a
 * container. It keeps its own size and simply travels.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedElementTransform(key: String, enabled: Boolean = true): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    val animatedScope = LocalNavAnimatedScope.current
    if (!enabled || sharedScope == null || animatedScope == null) {
        this
    } else {
        with(sharedScope) {
            this@composed.sharedElement(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = animatedScope,
                boundsTransform = containerBounds,
            )
        }
    }
}

/** True when a container transform can actually run right now. */
@Composable
fun isTransformAvailable(): Boolean =
    LocalSharedTransitionScope.current != null && LocalNavAnimatedScope.current != null
