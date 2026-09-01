package com.tzvi.kickoff.feature.matchdetail

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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPrediction
import com.tzvi.kickoff.core.model.TeamForm
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * What can be said about a match before it starts.
 *
 * This tab exists because the honest answer to "show me the expected line-up" is that
 * there isn't one. API-Football publishes a confirmed XI twenty to forty minutes before
 * kick-off and has no probable-line-up endpoint at all, and no free source with rights to
 * redistribute one covers the competitions followed here. What it does compute is a
 * Poisson model over both sides' season form, which is what this screen shows - together
 * with the form strings and the head-to-head it is built from, so the number can be
 * argued with rather than just believed.
 */
@Composable
internal fun MatchPreviewSection(
    match: Match,
    prediction: MatchPrediction?,
    headToHead: List<Match>,
    isLoading: Boolean,
    predictionsCovered: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when {
            prediction != null -> {
                SectionHeader(title = "The odds of it")
                OddsBar(prediction = prediction, match = match)
                val advice = prediction.advice
                if (advice != null) {
                    Spacer(Modifier.height(10.dp))
                    AdviceCard(advice = advice, comment = prediction.winnerComment)
                }
                if (prediction.homeForm != null || prediction.awayForm != null) {
                    Spacer(Modifier.height(14.dp))
                    SectionHeader(title = "Form")
                    FormRow(
                        homeName = match.home.shortName ?: match.home.name,
                        awayName = match.away.shortName ?: match.away.name,
                        home = prediction.homeForm,
                        away = prediction.awayForm,
                    )
                }
            }

            isLoading -> LoadingState(label = "Reading the form")

            // Coverage is a fact about the competition, not a failure of this fixture, so
            // it gets its own wording: no amount of pulling to refresh will produce one.
            !predictionsCovered -> EmptyState(
                title = "No pre-match read for this competition",
                body = "The provider does not compute odds or form for " +
                    "${match.leagueName}. Line-ups and events still arrive as normal.",
                icon = Icons.Outlined.Insights,
            )

            else -> EmptyState(
                title = "Nothing to go on yet",
                body = "No pre-match numbers have been published for this fixture.",
                icon = Icons.Outlined.Insights,
            )
        }

        if (headToHead.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "When they last met")
            // Read-only: these come straight from the provider and were never written to
            // the cache, so a tap would open a match screen with nothing behind it.
            headToHead.take(HEAD_TO_HEAD_SHOWN).forEach { meeting ->
                HeadToHeadRow(match = meeting)
            }
        }
    }
}

/**
 * Home / draw / away as one bar.
 *
 * Three separate bars would invite reading each against a full width, which is exactly
 * the wrong intuition: these are shares of one certainty and they add to a hundred.
 */
@Composable
private fun OddsBar(prediction: MatchPrediction, match: Match) {
    val home = prediction.homePercent ?: 0
    val draw = prediction.drawPercent ?: 0
    val away = prediction.awayPercent ?: 0
    val total = (home + draw + away).coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .clip(KickoffShapes.small),
        ) {
            Segment(weight = home, total = total, color = MaterialTheme.colorScheme.primary)
            Segment(
                weight = draw,
                total = total,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Segment(weight = away, total = total, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            OddsLabel(
                name = match.home.shortName ?: match.home.name,
                percent = prediction.homePercent,
                align = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            OddsLabel(
                name = "Draw",
                percent = prediction.drawPercent,
                align = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            OddsLabel(
                name = match.away.shortName ?: match.away.name,
                percent = prediction.awayPercent,
                align = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(
    weight: Int,
    total: Int,
    color: Color,
) {
    if (weight <= 0) return
    Box(
        Modifier
            .weight(weight.toFloat() / total)
            .fillMaxWidth()
            .height(BarHeight)
            .background(color),
    )
}

@Composable
private fun OddsLabel(
    name: String,
    percent: Int?,
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "${percent ?: 0}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdviceCard(advice: String, comment: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = KickoffShapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = advice,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (comment != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FormRow(homeName: String, awayName: String, home: TeamForm?, away: TeamForm?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FormColumn(name = homeName, form = home, modifier = Modifier.weight(1f))
        FormColumn(name = awayName, form = away, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FormColumn(name: String, form: TeamForm?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        val results = form?.recentResults
        if (results.isNullOrBlank()) {
            Text(
                text = "No form recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                results.forEach { ResultPip(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        FormStat("Scored", form?.goalsForAverage?.let { "$it a game" })
        FormStat("Conceded", form?.goalsAgainstAverage?.let { "$it a game" })
        FormStat("Clean sheets", form?.cleanSheets?.toString())
    }
}

/** W, D or L as a coloured disc: a run of form is read as a shape, not as letters. */
@Composable
private fun ResultPip(result: Char) {
    val colour = when (result.uppercaseChar()) {
        'W' -> MaterialTheme.colorScheme.primary
        'D' -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.error
    }
    val onColour = when (result.uppercaseChar()) {
        'W' -> MaterialTheme.colorScheme.onPrimary
        'D' -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onError
    }
    Box(
        modifier = Modifier
            .size(PipSize)
            .clip(KickoffShapes.small)
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = result.uppercaseChar().toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = onColour,
        )
    }
}

@Composable
private fun FormStat(label: String, value: String?) {
    Row(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HeadToHeadRow(match: Match) {
    val score = match.score
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = match.home.shortName ?: match.home.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = score?.toString() ?: "v",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            text = match.away.shortName ?: match.away.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val HEAD_TO_HEAD_SHOWN = 6
private val BarHeight = 14.dp
private val PipSize = 20.dp

@Preview(name = "Match preview", widthDp = 400)
@Composable
private fun MatchPreviewSectionPreview() {
    KickoffTheme {
        MatchPreviewSection(
            match = previewMatch(),
            prediction = MatchPrediction(
                homePercent = 52,
                drawPercent = 25,
                awayPercent = 23,
                advice = "Double chance : Arsenal or draw",
                winnerName = "Arsenal",
                winnerComment = "Win or draw",
                goalsLine = "-3.5",
                homeForm = TeamForm("WWDLW", "strong", "strong", "2.1", "0.8", 9),
                awayForm = TeamForm("LDWLL", "average", "weak", "1.2", "1.7", 3),
            ),
            headToHead = emptyList(),
            isLoading = false,
            predictionsCovered = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
