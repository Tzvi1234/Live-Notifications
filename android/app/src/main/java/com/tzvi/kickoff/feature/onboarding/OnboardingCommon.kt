package com.tzvi.kickoff.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/**
 * The fixed frame above the pager: how far along you are, what this step is called, what
 * it wants, and what it has so far.
 *
 * It sits outside the pager on purpose. Headings that scroll with their page leave the
 * user staring at a text field or a grid with nothing naming it, which is most of what
 * made the flow hard to read.
 */
@Composable
internal fun StepHeader(
    step: OnboardingStep,
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
            text = step.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = step.subtitle,
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
 * Four segments, one per step that asks for something. Segments read as progress at a
 * glance in a way a row of identical dots does not, and it lives at the top where a
 * progress bar belongs rather than fighting the buttons at the bottom.
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
 * Content parallax: a page's contents trail the page itself, so a swipe reads as one
 * object moving rather than two panes swapping.
 *
 * `clip` is what makes it safe. A graphicsLayer does not clip by default, so translating
 * a page's contents sideways lets the page *next door* - laid out just off-screen - paint
 * its heading and cards into the visible edge, which is exactly the mess it looked like.
 * Clipping confines every page's drawing to its own slot.
 */
internal fun Modifier.pageMotion(pagerState: PagerState, page: Int): Modifier =
    graphicsLayer {
        // Read inside the layer block, not in composition: the offset changes every frame
        // of a drag, and a composition-time read would re-run the whole page for each one.
        val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        clip = true
        translationX = size.width * offset * CONTENT_LAG
        // Fully transparent one page out: even if a future layout change defeats the clip,
        // a neighbouring page has nothing left to paint into the margin.
        alpha = (1f - abs(offset)).coerceIn(0f, 1f)
    }

private const val CONTENT_LAG = 0.16f

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
        StepHeader(step = OnboardingStep.TEAMS, status = "3 PICKED")
    }
}
