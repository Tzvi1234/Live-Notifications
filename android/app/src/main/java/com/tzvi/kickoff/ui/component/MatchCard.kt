package com.tzvi.kickoff.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.motion.TransformKeys
import com.tzvi.kickoff.ui.motion.containerTransform
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One fixture in a list.
 *
 * Tapping it hands its bounds to the match screen, so the card grows into the detail
 * view instead of being replaced by it - see [containerTransform].
 */
@Composable
fun MatchCard(
    match: Match,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showLeague: Boolean = true,
) {
    Card(
        onClick = { onClick(match.id) },
        modifier = modifier
            .fillMaxWidth()
            .containerTransform(TransformKeys.matchCard(match.id), KickoffShapes.medium),
        shape = KickoffShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (showLeague) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = listOfNotNull(
                            match.leagueName.takeIf { it.isNotBlank() },
                            match.round,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (match.isLive) LivePill()
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(match.home, Modifier.weight(1f))
                CentreBlock(match)
                TeamColumn(match.away, Modifier.weight(1f), trailing = true)
            }
        }
    }
}

@Composable
private fun TeamColumn(team: Team, modifier: Modifier = Modifier, trailing: Boolean = false) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (trailing) Arrangement.End else Arrangement.Start,
    ) {
        if (trailing) {
            TeamName(team, Modifier.weight(1f, fill = false), TextAlign.End)
            Spacer(Modifier.width(8.dp))
            TeamCrest(team, size = 30.dp)
        } else {
            TeamCrest(team, size = 30.dp)
            Spacer(Modifier.width(8.dp))
            TeamName(team, Modifier.weight(1f, fill = false), TextAlign.Start)
        }
    }
}

@Composable
private fun TeamName(team: Team, modifier: Modifier = Modifier, align: TextAlign) {
    Text(
        text = team.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

/**
 * The middle column: the score once there is one, the kick-off time before that.
 *
 * The score slides rather than cuts, so a goal is visible even if the user was looking
 * at the other side of the screen when it landed.
 */
@Composable
private fun CentreBlock(match: Match) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp),
    ) {
        AnimatedContent(
            targetState = match.score,
            transitionSpec = {
                (slideInVertically(Motion.spatialExpressive()) { it / 2 } +
                    fadeIn(Motion.effects(Motion.Duration.SHORT)))
                    .togetherWith(
                        slideOutVertically(Motion.spatial()) { -it / 2 } +
                            fadeOut(Motion.effects(Motion.Duration.SHORT)),
                    )
            },
            label = "score",
        ) { score ->
            Text(
                text = score?.let { "${it.home} – ${it.away}" }
                    ?: TIME_FORMAT.format(match.kickoffAt.atZone(ZoneId.systemDefault())),
                style = if (score != null) KickoffTextStyles.scoreMedium
                else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        val caption = when {
            match.isLive -> match.clockLabel
            match.phase.isFinished -> "FT"
            match.phase == MatchPhase.OFF -> "Off"
            else -> null
        }
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .clip(KickoffShapes.extraSmall)
                    .background(
                        if (match.isLive) KickoffTheme.accents.live.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = caption,
                    style = KickoffTextStyles.clock,
                    color = if (match.isLive) KickoffTheme.accents.live
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Preview
@Composable
private fun MatchCardPreview() {
    KickoffTheme {
        MatchCard(
            match = Match(
                id = 1,
                leagueId = 39,
                leagueName = "Premier League",
                leagueLogoUrl = null,
                round = "Matchweek 4",
                kickoffAt = Instant.now(),
                venue = null,
                phase = MatchPhase.SECOND_HALF,
                elapsedMinutes = 67,
                extraMinutes = null,
                home = Team(1, "Arsenal", "ARS", null),
                away = Team(2, "Chelsea", "CHE", null),
                score = Score(2, 1),
            ),
            onClick = {},
        )
    }
}
