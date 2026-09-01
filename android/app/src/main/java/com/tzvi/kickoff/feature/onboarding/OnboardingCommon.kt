package com.tzvi.kickoff.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlin.math.abs

/** The handful of measurements the flow reuses, kept in one place rather than inline. */
internal object OnboardingSpacing {
    /** The single left/right margin. Every page lines up on it, including the header. */
    val screen = 24.dp
    val block = 18.dp
    val card = 18.dp
    val tight = 8.dp
}

private val RAIL_HEIGHT = 4.dp

/** Title and one line of explanation, resolved per step because SOURCE changes with the pick. */
internal data class StepCopy(val title: String, val subtitle: String)

internal fun copyFor(step: OnboardingStep, state: OnboardingUiState): StepCopy = when (step) {
    OnboardingStep.WELCOME -> StepCopy("matchUP", "")

    // The only step whose heading depends on an answer: once something is picked it is
    // showing that one thing, and it should say which rather than make you remember.
    OnboardingStep.SOURCE -> when (state.chosenSource) {
        ConfiguredSource.DEMO -> StepCopy(
            title = "Demo data it is",
            subtitle = "Real clubs and crests, fixtures invented around right now.",
        )

        ConfiguredSource.API_FOOTBALL -> StepCopy(
            title = "Paste your key",
            subtitle = "The one on your API-Football dashboard, under Account.",
        )

        ConfiguredSource.BACKEND -> StepCopy(
            title = "You're on the matchUP server",
            subtitle = "Nothing to set up. Everything below is optional.",
        )

        else -> StepCopy(
            title = "Where do scores come from?",
            subtitle = "matchUP has no feed of its own. Pick one.",
        )
    }

    OnboardingStep.LEAGUES -> StepCopy(
        title = "Pick your competitions",
        subtitle = "This decides which squads the next step offers.",
    )

    OnboardingStep.TEAMS -> StepCopy(
        title = "Pick the teams you follow",
        subtitle = "Every match they play gets a live card of its own.",
    )

    OnboardingStep.ALERTS -> StepCopy(
        title = "Turn the live card on",
        subtitle = "One notification per match that keeps editing itself.",
    )

    OnboardingStep.READY -> StepCopy(
        title = "That's everything",
        subtitle = "Here is what matchUP will do from now on.",
    )
}

/**
 * The fixed frame above the pager: how far along you are, what this step is called, what
 * it wants, and what it has so far.
 *
 * It sits outside the pager on purpose. Headings that scroll with their page leave the
 * user staring at a text field or a grid with nothing naming it.
 */
@Composable
internal fun StepHeader(
    step: OnboardingStep,
    copy: StepCopy,
    status: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = OnboardingSpacing.screen,
                end = OnboardingSpacing.screen,
                top = OnboardingSpacing.tight,
                bottom = OnboardingSpacing.block,
            ),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "STEP ${step.number} OF ${OnboardingStep.counted.size}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = status != null,
                enter = scaleIn(Motion.spatial()) + fadeIn(Motion.effects(Motion.Duration.SHORT)),
                exit = scaleOut(Motion.spatial()) + fadeOut(Motion.effects(Motion.Duration.SHORT)),
            ) {
                MetaChip(
                    text = status.orEmpty(),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        StepProgress(step = step)
        Spacer(Modifier.height(2.dp))

        Text(
            text = copy.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = copy.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Pinned to two lines so the header is the same height on every step and the
            // page under it never shifts as you swipe.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One segment per step that asks for something. Segments read as progress at a glance in
 * a way a row of identical dots does not, and it lives at the top where a progress bar
 * belongs rather than fighting the buttons at the bottom.
 */
@Composable
private fun StepProgress(step: OnboardingStep, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OnboardingStep.counted.forEach { candidate ->
            val done = candidate.ordinal <= step.ordinal
            val colour by animateColorAsState(
                targetValue = if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = Motion.effects(Motion.Duration.MEDIUM),
                label = "step-rail-colour",
            )
            // The current segment is the wide one, so "where am I" survives a glance.
            val weight by animateFloatAsState(
                targetValue = if (candidate == step) 2f else 1f,
                animationSpec = Motion.floatSpring(),
                label = "step-rail-weight",
            )
            Box(
                Modifier
                    .weight(weight)
                    .height(RAIL_HEIGHT)
                    .clip(KickoffShapeTokens.pill)
                    .background(colour),
            )
        }
    }
}

/**
 * Deals the page's blocks in, one after another, the first time it is arrived at.
 *
 * A page that appears all at once reads as a form. Dealt in, it reads as something being
 * laid out for you - and the eye lands on the first block rather than on the whole page.
 * Once dealt, a page stays dealt: swiping back and forth must not re-run the sequence.
 */
@Composable
internal fun StaggeredEntrance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(EntranceScope) -> Unit,
) {
    var dealt by remember { mutableStateOf(false) }
    if (visible) dealt = true
    Column(modifier = modifier) { content(EntranceScope(dealt)) }
}

/** Hands each block its own delay; index 0 arrives first. */
internal class EntranceScope(private val dealt: Boolean) {
    @Composable
    fun Block(index: Int, content: @Composable () -> Unit) {
        AnimatedVisibility(
            visible = dealt,
            enter = fadeIn(tween(Motion.Duration.MEDIUM, delayMillis = index * STAGGER_MS)) +
                slideInVertically(
                    animationSpec = tween(
                        Motion.Duration.LONG,
                        delayMillis = index * STAGGER_MS,
                        easing = Motion.Easings.emphasised,
                    ),
                    initialOffsetY = { it / 5 },
                ),
            // Nothing leaves: the page is clipped away by the pager itself.
            exit = fadeOut(tween(0)),
        ) {
            content()
        }
    }
}

private const val STAGGER_MS = 70

/** An icon plus a line of copy, used for the welcome page's three promises. */
@Composable
internal fun FeatureLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The swipe, with depth.
 *
 * The page's contents trail the page itself and tip slightly away from you as they go, so
 * a swipe reads as one deck of cards turning rather than two flat panes swapping. The
 * rotation is small on purpose - past a few degrees it stops looking like depth and starts
 * looking like a broken transform.
 *
 * `clip` is what makes it safe. A graphicsLayer does not clip by default, so translating a
 * page's contents sideways lets the page next door - laid out just off-screen - paint into
 * the visible edge. Clipping confines every page's drawing to its own slot.
 */
internal fun Modifier.pageMotion(pagerState: PagerState, page: Int): Modifier =
    graphicsLayer {
        // Read inside the layer block, not in composition: the offset changes every frame
        // of a drag, and a composition-time read would re-run the whole page for each one.
        val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        val distance = abs(offset).coerceIn(0f, 1f)

        clip = true
        cameraDistance = 14f * density
        translationX = size.width * offset * CONTENT_LAG
        rotationY = offset * -TILT_DEGREES
        val scale = lerp(1f, REAR_SCALE, distance)
        scaleX = scale
        scaleY = scale
        // Fully transparent one page out: even if a future layout change defeats the clip,
        // a neighbouring page has nothing left to paint into the margin.
        alpha = 1f - distance
    }

private const val CONTENT_LAG = 0.16f
private const val TILT_DEGREES = 9f
private const val REAR_SCALE = 0.88f

/** Sample content for the @Preview functions in this package. */
internal object OnboardingSamples {
    val leagues = listOf(
        League(39, "Premier League", "England", null, 2026),
        League(140, "La Liga", "Spain", null, 2026),
        League(135, "Serie A", "Italy", null, 2026),
        League(78, "Bundesliga", "Germany", null, 2026),
    )

    val teams = listOf(
        TeamOption(Team(42, "Arsenal", "Arsenal", null, "England"), leagues[0]),
        TeamOption(Team(49, "Chelsea", "Chelsea", null, "England"), leagues[0]),
        TeamOption(Team(529, "Barcelona", "Barcelona", null, "Spain"), leagues[1]),
        TeamOption(Team(541, "Real Madrid", "Real Madrid", null, "Spain"), leagues[1]),
    )
}

@Preview(name = "Step header")
@Composable
private fun StepHeaderPreview() {
    KickoffTheme {
        StepHeader(
            step = OnboardingStep.TEAMS,
            copy = copyFor(OnboardingStep.TEAMS, OnboardingUiState()),
            status = "3 PICKED",
        )
    }
}
