package com.tzvi.kickoff.feature.predict

import androidx.compose.material.icons.outlined.Shield
import com.tzvi.kickoff.core.model.Rulebook
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.core.model.GroupMember
import com.tzvi.kickoff.core.model.PredictScoring
import com.tzvi.kickoff.core.model.PredictionEntry
import com.tzvi.kickoff.ui.component.Avatar
import com.tzvi.kickoff.ui.component.AvatarDefaults
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One fixture with a pair of steppers under it.
 *
 * Steppers rather than a text field because a scoreline is two small integers and a
 * keyboard is four taps and a dismissal to enter one of them. The commit is explicit:
 * sending on every tap would put 1-0 and 2-0 on the wire on the way to typing 2-1.
 */
@Composable
internal fun GuessCard(
    fixture: GroupFixture,
    draft: Pair<Int, Int>,
    isDirty: Boolean,
    isSaving: Boolean,
    onAdjust: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fixture.match.leagueName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = fixture.statusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fixture.isOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(
                    name = fixture.match.home.name,
                    crestUrl = fixture.match.home.crestUrl,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = fixture.actualScoreLabel() ?: "v",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                TeamColumn(
                    name = fixture.match.away.name,
                    crestUrl = fixture.match.away.crestUrl,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (fixture.isOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stepper(value = draft.first, onChange = { onAdjust(it, 0) })
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Stepper(value = draft.second, onChange = { onAdjust(0, it) })
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onSubmit,
                    enabled = isDirty && !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            isSaving -> "Sending"
                            isDirty -> "Lock it in"
                            fixture.myPrediction != null -> "Saved"
                            else -> "Lock it in"
                        },
                    )
                }
            } else {
                // Once it is locked the guesses become public, which is the whole point of
                // locking: up to here nobody could see anybody's.
                val everyone = listOfNotNull(fixture.myPrediction) + fixture.others
                if (everyone.isEmpty()) {
                    Text(
                        text = "Nobody guessed this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    everyone.forEach { entry -> GuessRow(entry = entry) }
                }
            }
        }
    }
}

@Composable
private fun TeamColumn(name: String, crestUrl: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CrestImage(url = crestUrl, fallback = name, size = CrestSize)
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onChange(-1) },
            enabled = value > 0,
            modifier = Modifier.size(StepperButton),
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "One fewer")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )
        FilledTonalIconButton(
            onClick = { onChange(1) },
            modifier = Modifier.size(StepperButton),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "One more")
        }
    }
}

@Composable
private fun GuessRow(entry: PredictionEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = entry.displayName, url = entry.avatarUrl)
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.scoreLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val points = entry.points
        if (points != null) {
            Spacer(Modifier.width(8.dp))
            PointsPill(points = points)
        }
    }
}

/** Three, one or nothing - the whole scoring rule, shown rather than explained. */
@Composable
internal fun PointsPill(points: Int, modifier: Modifier = Modifier) {
    val container = when (points) {
        PredictScoring.EXACT -> MaterialTheme.colorScheme.primary
        PredictScoring.OUTCOME -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (points) {
        PredictScoring.EXACT -> MaterialTheme.colorScheme.onPrimary
        PredictScoring.OUTCOME -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "+$points",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = content,
        modifier = modifier
            .clip(KickoffShapeTokens.pill)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}


/** One row of the table: who, how many points, and how they earned them. */
@Composable
internal fun LeaderboardRow(
    position: Int,
    member: GroupMember,
    predicted: PredictionEntry?,
    modifier: Modifier = Modifier,
    /** Whoever created the group. Marked even on a table where nothing is settled. */
    isCaptain: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Avatar(name = member.displayName, url = member.avatarUrl)
            if (isCaptain) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = "Captain",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${member.exactCount} exact · ${member.outcomeCount} called right",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The guess for the match in play sits on the member's own card, which is
            // what makes the table worth watching while the match is on.
            if (predicted != null) {
                Text(
                    text = predicted.scoreLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Text(
                text = member.points.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal fun GroupFixture.statusLabel(): String = when {
    isLive -> match.clockLabel.takeIf { it.isNotBlank() } ?: "Live"
    match.phase.isFinished -> "Full time"
    locked -> "Locked"
    else -> countdownLabel()
}

internal fun GroupFixture.actualScoreLabel(): String? = match.score?.toString()

/**
 * How long is left to guess, in the largest unit that is still honest.
 *
 * "2 days" is more use than "51 hours", and "8 min" matters in a way "0 hours" does not.
 */
private fun GroupFixture.countdownLabel(): String {
    val left = Duration.between(Instant.now(), match.kickoffAt)
    return when {
        left.isNegative -> "Kicking off"
        left.toDays() >= 1 -> "${left.toDays()}d left"
        left.toHours() >= 1 -> "${left.toHours()}h left"
        left.toMinutes() >= 1 -> "${left.toMinutes()} min left"
        else -> "Closing"
    }
}

internal fun Instant.chatTime(): String =
    CHAT_TIME.format(atZone(ZoneId.systemDefault()))

private val CHAT_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private val CrestSize = 34.dp
private val StepperButton = 40.dp

private fun previewFixtureMatch() = Match(
    id = 1,
    leagueId = 39,
    leagueName = "Premier League",
    leagueLogoUrl = null,
    round = null,
    kickoffAt = Instant.now().plusSeconds(7_200),
    venue = null,
    phase = MatchPhase.SCHEDULED,
    elapsedMinutes = null,
    extraMinutes = null,
    home = Team(42, "Arsenal", "ARS", null),
    away = Team(49, "Chelsea", "CHE", null),
    score = null,
)

@Preview(name = "Guess card", widthDp = 380)
@Composable
private fun GuessCardPreview() {
    KickoffTheme {
        GuessCard(
            fixture = GroupFixture(
                match = previewFixtureMatch(),
                locked = false,
                myPrediction = null,
                others = emptyList(),
            ),
            draft = 2 to 1,
            isDirty = true,
            isSaving = false,
            onAdjust = { _, _ -> },
            onSubmit = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * The rulebook, as the server publishes it.
 *
 * Rendered from data rather than written into the app: the server is the only place
 * scoring actually happens, and a second copy of the rules here is a second copy to
 * disagree with the first. A backend too old to send them shows the sheet's one honest
 * alternative - that it cannot say - rather than a table of invented numbers.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RulesSheet(rules: Rulebook?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "How points work",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            if (rules == null) {
                Text(
                    text = "This server has not published its scoring rules. Points are " +
                        "still awarded - the table is the server's own - but the breakdown " +
                        "is only available from a newer backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = "For the scoreline",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            rules.scoring.forEach { row ->
                RuleLine(label = row.label, value = "${row.points} pts")
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Multiplied by the round",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            rules.multipliers.forEach { row ->
                RuleLine(label = row.label, value = "×${row.multiplier}")
            }

            if (rules.notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                rules.notes.forEach { note ->
                    Text(
                        text = "• $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
