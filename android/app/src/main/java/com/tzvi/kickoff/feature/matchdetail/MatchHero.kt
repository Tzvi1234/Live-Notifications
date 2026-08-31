package com.tzvi.kickoff.feature.matchdetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.LivePill
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.motion.TransformKeys
import com.tzvi.kickoff.ui.motion.containerTransform
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The scoreboard the fixture card grows into.
 *
 * It carries the same transform key the card in the list published, so the two are one
 * object as far as the transition is concerned - which is also why the crests are shared
 * elements rather than fresh images that happen to point at the same URL.
 */
@Composable
internal fun MatchHero(match: Match, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .containerTransform(TransformKeys.matchCard(match.id), KickoffShapeTokens.scoreboard),
        shape = KickoffShapeTokens.scoreboard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompetitionLine(match)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroTeam(match.home, Modifier.weight(1f))
                HeroScore(match)
                HeroTeam(match.away, Modifier.weight(1f))
            }
            HeroFooter(match)
        }
    }
}

@Composable
private fun CompetitionLine(match: Match) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(
                match.leagueName.takeIf { it.isNotBlank() },
                match.round,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (match.isLive) {
            Spacer(Modifier.width(8.dp))
            LivePill()
        }
    }
}

@Composable
private fun HeroTeam(team: Team, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TeamCrest(team = team, size = CrestSize, shared = true)
        Spacer(Modifier.height(10.dp))
        Text(
            text = team.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroScore(match: Match) {
    Column(
        modifier = Modifier.width(ScoreColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = match.score,
            transitionSpec = {
                (
                    slideInVertically(Motion.spatialExpressive()) { it / 2 } +
                        fadeIn(Motion.effects(Motion.Duration.SHORT))
                    ).togetherWith(
                    slideOutVertically(Motion.spatial()) { -it / 2 } +
                        fadeOut(Motion.effects(Motion.Duration.SHORT)),
                )
            },
            label = "hero-score",
        ) { score ->
            Text(
                text = score?.let { "${it.home} – ${it.away}" } ?: match.kickoffTime(),
                style = if (score != null) KickoffTextStyles.scoreLarge
                else KickoffTextStyles.scoreMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        MetaChip(
            text = match.statusLabel(),
            container = if (match.isLive) KickoffTheme.accents.live.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            content = if (match.isLive) KickoffTheme.accents.live
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeroFooter(match: Match) {
    val chips = buildList {
        match.halfTimeScore?.let { add("HT $it") }
        match.penaltyScore?.let { add("PENS $it") }
    }
    if (chips.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { MetaChip(it) }
        }
    }
    val venue = match.venue?.takeIf { it.isNotBlank() }
    if (venue != null) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = venue,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The match clock as a 0..90 track.
 *
 * Extra time and penalties pin at the end rather than overflowing - that is what
 * [Match.progressMinutes] already decides - and the tick marks the interval so the bar
 * reads as two halves rather than as a download.
 */
@Composable
internal fun MatchClockBar(match: Match, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = match.progressMinutes / Match.REGULATION_MINUTES.toFloat(),
        animationSpec = Motion.floatSpring(),
        label = "match-clock",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = if (match.isLive) KickoffTheme.accents.live
    else MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outline
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val halfPoint = Match.HALF_MINUTES / Match.REGULATION_MINUTES.toFloat()

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("0'", style = KickoffTextStyles.clock, color = captionColor)
            Text(
                text = match.statusLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (match.isLive) KickoffTheme.accents.live else captionColor,
            )
            Text("90'", style = KickoffTextStyles.clock, color = captionColor)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ClockBarHeight),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = trackColor, cornerRadius = radius)
            if (progress > 0f) {
                drawRoundRect(
                    color = fillColor,
                    size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                    cornerRadius = radius,
                )
            }
            val tickX = size.width * halfPoint
            drawLine(
                color = tickColor,
                start = Offset(tickX, 0f),
                end = Offset(tickX, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = "HT",
                style = KickoffTextStyles.clock,
                color = captionColor,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** "67'", "HT", "Full time", or the kick-off day for a fixture that has not started. */
private fun Match.statusLabel(): String = when {
    isLive -> clockLabel.ifBlank { "Live" }
    phase.isFinished -> "Full time"
    phase == MatchPhase.OFF -> "Called off"
    else -> DATE_FORMAT.format(kickoffAt.atZone(ZoneId.systemDefault()))
}

private fun Match.kickoffTime(): String =
    TIME_FORMAT.format(kickoffAt.atZone(ZoneId.systemDefault()))

private val CrestSize = 64.dp
private val ScoreColumnWidth = 132.dp
private val ClockBarHeight = 8.dp

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Preview(name = "Hero - live", widthDp = 400)
@Composable
private fun MatchHeroLivePreview() {
    KickoffTheme {
        Column(Modifier.padding(16.dp)) {
            MatchHero(
                previewMatch(
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                    halfTimeScore = Score(1, 1),
                ),
            )
            Spacer(Modifier.height(16.dp))
            MatchClockBar(
                previewMatch(
                    phase = MatchPhase.SECOND_HALF,
                    elapsedMinutes = 67,
                    score = Score(2, 1),
                ),
            )
        }
    }
}

@Preview(name = "Hero - scheduled", widthDp = 400)
@Composable
private fun MatchHeroScheduledPreview() {
    KickoffTheme {
        Column(Modifier.padding(16.dp)) {
            MatchHero(previewMatch())
            Spacer(Modifier.height(16.dp))
            MatchClockBar(previewMatch())
        }
    }
}
