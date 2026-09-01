package com.tzvi.kickoff.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.PlayerCard
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOff

/**
 * Who this player is, and what they did in the match you were just looking at.
 *
 * A sheet rather than a screen on purpose: you arrived here by tapping a shirt on a pitch
 * and you are going to tap another one in a moment, so the line-up stays behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    request: PlayerRequest,
    onDismiss: () -> Unit,
    viewModel: PlayerSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.runtime.LaunchedEffect(request) { viewModel.load(request) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            when {
                state.loading -> LoadingState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    label = "Reading the match",
                )

                state.card == null -> EmptyState(
                    title = "Nothing on this player",
                    body = state.error
                        ?: "The source has no record for them in this match. That is normal " +
                        "for a substitute who never came on, and for competitions the " +
                        "provider only covers at final-score level.",
                    icon = Icons.Outlined.PersonOff,
                )

                else -> PlayerBody(card = requireNotNull(state.card))
            }
        }
    }
}

@Composable
private fun PlayerBody(card: PlayerCard, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(KickoffShapeTokens.crest)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                CrestImage(url = card.photoUrl, fallback = card.name, size = 72.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(
                    card.teamName,
                    card.match?.position?.let(::positionName)
                        ?: card.profile?.position,
                    card.match?.number?.let { "#$it" } ?: card.profile?.number?.let { "#$it" },
                ).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            card.match?.rating?.let { rating ->
                Spacer(Modifier.width(12.dp))
                RatingBadge(rating)
            }
        }

        val profile = card.profile
        if (profile != null) {
            Spacer(Modifier.height(18.dp))
            val facts = listOfNotNull(
                profile.age?.let { "Age" to it.toString() },
                profile.nationality?.let { "Nationality" to it },
                profile.height?.let { "Height" to it },
                profile.weight?.let { "Weight" to it },
                profile.birthPlace?.let { "Born" to it },
            )
            if (facts.isNotEmpty()) {
                FactRow(facts)
            }
        }

        val match = card.match
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        if (match == null || !match.hasAnything) {
            Text(
                text = "No match statistics for this player yet. They appear once the " +
                    "provider has recorded a touch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            text = "In this match",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (match.startedOnBench) {
            Text(
                text = "Started on the bench",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        StatGrid(match)
    }
}

@Composable
private fun RatingBadge(rating: String, modifier: Modifier = Modifier) {
    // Six is the provider's midpoint; above it is a good game, below it is not.
    val value = rating.toFloatOrNull()
    val container = when {
        value == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        value >= 7.5f -> KickoffTheme.accents.win
        value >= 6.5f -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .clip(KickoffShapeTokens.chip)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = rating,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = "RATING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun FactRow(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        facts.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Only the stats the provider actually recorded.
 *
 * A null is not a zero - it means this competition does not track that number for this
 * player - so a missing stat is left out rather than printed as 0, which would be a claim
 * the data does not support.
 */
@Composable
private fun StatGrid(stats: PlayerMatchStats, modifier: Modifier = Modifier) {
    val cells = buildList {
        stats.minutes?.let { add("Minutes" to "$it'") }
        stats.goals?.takeIf { it > 0 }?.let { add("Goals" to "$it") }
        stats.assists?.takeIf { it > 0 }?.let { add("Assists" to "$it") }
        stats.shotsTotal?.let { add("Shots" to "$it") }
        stats.shotsOnTarget?.let { add("On target" to "$it") }
        stats.passesTotal?.let { add("Passes" to "$it") }
        stats.passAccuracy?.let { add("Pass accuracy" to it) }
        stats.passesKey?.let { add("Key passes" to "$it") }
        stats.saves?.let { add("Saves" to "$it") }
        stats.conceded?.let { add("Conceded" to "$it") }
        stats.tackles?.let { add("Tackles" to "$it") }
        stats.interceptions?.let { add("Interceptions" to "$it") }
        stats.duelsWon?.let { won ->
            add("Duels won" to (stats.duelsTotal?.let { "$won/$it" } ?: "$won"))
        }
        stats.dribblesSuccessful?.let { done ->
            add("Dribbles" to (stats.dribbleAttempts?.let { "$done/$it" } ?: "$done"))
        }
        stats.foulsCommitted?.let { add("Fouls" to "$it") }
        stats.foulsDrawn?.let { add("Fouled" to "$it") }
        stats.offsides?.let { add("Offsides" to "$it") }
        stats.yellowCards?.takeIf { it > 0 }?.let { add("Yellow" to "$it") }
        stats.redCards?.takeIf { it > 0 }?.let { add("Red" to "$it") }
    }

    // Height is bounded so the grid can live inside the sheet's own scroll: two columns,
    // one row per pair, plus the row gaps.
    val rows = (cells.size + 1) / 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxWidth()
            .height((rows * StatRowHeight.value).dp),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cells.size) { index ->
            val (label, value) = cells[index]
            StatCell(label = label, value = value)
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapeTokens.chip)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}

private val StatRowHeight = 60.dp

private fun positionName(short: String): String = when (short.uppercase()) {
    "G" -> "Goalkeeper"
    "D" -> "Defender"
    "M" -> "Midfielder"
    "F" -> "Forward"
    else -> short
}

@Preview(name = "Player sheet")
@Composable
private fun PlayerBodyPreview() {
    KickoffTheme {
        PlayerBody(
            card = PlayerCard(
                id = 1,
                name = "Kai Havertz",
                photoUrl = null,
                teamName = "Arsenal",
                profile = null,
                match = PlayerMatchStats(
                    minutes = 78,
                    number = 29,
                    position = "F",
                    rating = "7.8",
                    goals = 1,
                    shotsTotal = 3,
                    shotsOnTarget = 2,
                    passesTotal = 41,
                    passAccuracy = "88%",
                    duelsTotal = 12,
                    duelsWon = 7,
                ),
            ),
        )
    }
}
