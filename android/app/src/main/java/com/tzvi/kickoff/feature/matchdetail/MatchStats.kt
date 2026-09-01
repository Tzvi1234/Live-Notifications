package com.tzvi.kickoff.feature.matchdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * Team statistics as two bars growing out from the middle.
 *
 * A stat only appears when both sides have a value the view model could parse - half a
 * comparison is worse than none, because the empty half reads as a zero.
 */
@Composable
internal fun MatchStatsSection(
    stats: List<StatComparison>,
    match: Match,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty()) {
        StatsEmptyState(match, modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        stats.forEach { StatRow(it) }
    }
}

@Composable
private fun StatRow(stat: StatComparison) {
    val homeFraction by animateFloatAsState(
        targetValue = stat.homeFraction.coerceIn(0f, 1f),
        animationSpec = Motion.floatSpring(),
        label = "stat-home-${stat.label}",
    )
    val awayFraction by animateFloatAsState(
        targetValue = stat.awayFraction.coerceIn(0f, 1f),
        animationSpec = Motion.floatSpring(),
        label = "stat-away-${stat.label}",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatValue(stat.homeLabel, TextAlign.Start)
            Text(
                text = stat.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            StatValue(stat.awayLabel, TextAlign.End)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatBar(
                fraction = homeFraction,
                color = MaterialTheme.colorScheme.primary,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.weight(1f),
            )
            StatBar(
                fraction = awayFraction,
                color = MaterialTheme.colorScheme.tertiary,
                alignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatValue(text: String, align: TextAlign) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        maxLines = 1,
    )
}

@Composable
private fun StatBar(
    fraction: Float,
    color: Color,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(KickoffShapeTokens.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = alignment,
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(KickoffShapeTokens.pill)
                    .background(color),
            )
        }
    }
}

@Composable
private fun StatsEmptyState(match: Match, modifier: Modifier = Modifier) {
    when {
        match.phase == MatchPhase.SCHEDULED -> EmptyState(
            title = "Stats start when the match does",
            body = "Possession, shots and corners are counted from kick-off onwards.",
            icon = Icons.Outlined.Schedule,
            modifier = modifier,
        )
        match.phase == MatchPhase.OFF -> EmptyState(
            title = "No stats for this match",
            body = "The fixture was called off, so nothing was ever counted.",
            icon = Icons.Outlined.EventBusy,
            modifier = modifier,
        )
        else -> EmptyState(
            title = "No stats for this match",
            body = "The provider hasn't published team statistics for this fixture. Not " +
                "every competition supplies them.",
            icon = Icons.Outlined.SportsSoccer,
            modifier = modifier,
        )
    }
}

private val BarHeight = 8.dp

@Preview(name = "Stats", widthDp = 400)
@Composable
private fun MatchStatsPreview() {
    KickoffTheme {
        MatchStatsSection(
            stats = previewStats(),
            match = previewMatch(phase = MatchPhase.SECOND_HALF, elapsedMinutes = 67),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Stats - none yet", widthDp = 400)
@Composable
private fun MatchStatsEmptyPreview() {
    KickoffTheme {
        MatchStatsSection(stats = emptyList(), match = previewMatch())
    }
}
