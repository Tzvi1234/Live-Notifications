package com.tzvi.kickoff.feature.matchdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The match as it happened, newest first.
 *
 * Home incidents bank left and away incidents bank right of a spine carrying the minute,
 * which is what makes three goals in ten minutes read as one side taking the game over
 * rather than as a list of six lines.
 */
@Composable
internal fun MatchTimeline(
    entries: List<TimelineEntry>,
    match: Match,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        TimelineEmptyState(match, modifier)
        return
    }
    val spineColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            if (entry.isMarker) MarkerRow(entry, spineColor) else SidedRow(entry, spineColor)
        }
    }
}

@Composable
private fun SidedRow(entry: TimelineEntry, spineColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (entry.event.side == MatchSide.HOME) EventBlock(entry, alignEnd = true)
        }
        Box(
            modifier = Modifier
                .width(SpineWidth)
                .fillMaxHeight()
                .drawBehind { drawSpine(spineColor) },
            contentAlignment = Alignment.Center,
        ) {
            MetaChip(entry.event.minuteLabel.ifBlank { "·" })
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (entry.event.side == MatchSide.AWAY) EventBlock(entry, alignEnd = false)
        }
    }
}

/** Kick-off, the interval and full time belong to the spine itself, not to a side. */
@Composable
private fun MarkerRow(entry: TimelineEntry, spineColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .drawBehind { drawSpine(spineColor) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = KickoffShapeTokens.pill,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            Text(
                text = listOfNotNull(
                    entry.event.minuteLabel.takeIf { it.isNotBlank() },
                    entry.event.headline().takeIf { it.isNotBlank() },
                    entry.runningScore.toString().takeIf { entry.showsScore },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun EventBlock(entry: TimelineEntry, alignEnd: Boolean) {
    val event = entry.event
    val title = event.playerName?.takeIf { it.isNotBlank() } ?: event.headline()
    val supporting = event.supportingLine()

    Column(
        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The glyph always sits on the inner edge, beside the spine, so the two banks
            // mirror one another instead of both reading left to right.
            if (alignEnd) {
                EventTitle(title, TextAlign.End, Modifier.weight(1f, fill = false))
                EventGlyph(event.type)
            } else {
                EventGlyph(event.type)
                EventTitle(title, TextAlign.Start, Modifier.weight(1f, fill = false))
            }
        }
        if (supporting != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.showsScore) {
            Spacer(Modifier.height(5.dp))
            MetaChip(
                text = entry.runningScore.toString(),
                container = KickoffTheme.accents.goal.copy(alpha = 0.16f),
                content = KickoffTheme.accents.goal,
            )
        }
    }
}

@Composable
private fun EventTitle(text: String, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun EventGlyph(type: MatchEventType) {
    Text(text = type.glyph(), style = MaterialTheme.typography.titleMedium)
}

private fun DrawScope.drawSpine(color: Color) {
    val x = size.width / 2f
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.dp.toPx(),
    )
}

/** The second line: who assisted, who came off, what the card was for. */
private fun MatchEvent.supportingLine(): String? = when (type) {
    MatchEventType.GOAL, MatchEventType.PENALTY_GOAL ->
        assistName?.let { "Assist $it" }
            ?: detail?.takeUnless { it.equals("Normal Goal", ignoreCase = true) }
    MatchEventType.OWN_GOAL -> "Own goal"
    MatchEventType.PENALTY_MISSED -> "Penalty missed"
    MatchEventType.SUBSTITUTION -> assistName?.let { "For $it" }
    MatchEventType.SECOND_YELLOW -> "Second yellow"
    else -> detail
}?.takeIf { it.isNotBlank() }

private fun MatchEventType.glyph(): String = when (this) {
    MatchEventType.GOAL, MatchEventType.PENALTY_GOAL, MatchEventType.OWN_GOAL -> "⚽"
    MatchEventType.PENALTY_MISSED -> "✖"
    MatchEventType.YELLOW_CARD -> "🟨"
    MatchEventType.SECOND_YELLOW -> "🟨🟥"
    MatchEventType.RED_CARD -> "🟥"
    MatchEventType.SUBSTITUTION -> "🔁"
    MatchEventType.VAR -> "📺"
    MatchEventType.KICK_OFF -> "▶"
    MatchEventType.HALF_TIME -> "⏸"
    MatchEventType.FULL_TIME -> "⏹"
    MatchEventType.OTHER -> "•"
}

@Composable
private fun TimelineEmptyState(match: Match, modifier: Modifier = Modifier) {
    when {
        match.phase == MatchPhase.OFF -> EmptyState(
            title = "This match was called off",
            body = "Nothing was played, so there is nothing to show here.",
            icon = Icons.Outlined.EventBusy,
            modifier = modifier,
        )
        match.phase == MatchPhase.SCHEDULED -> EmptyState(
            title = "Nothing has happened yet",
            body = "Goals, cards and substitutions land here from the moment the referee " +
                "starts the match.",
            icon = Icons.Outlined.Schedule,
            modifier = modifier,
        )
        match.phase.isFinished -> EmptyState(
            title = "No events recorded",
            body = "The match finished without the provider publishing a single incident " +
                "for it.",
            icon = Icons.Outlined.Schedule,
            modifier = modifier,
        )
        else -> EmptyState(
            title = "Nothing yet",
            body = "Not a goal, a card or a substitution so far.",
            icon = Icons.Outlined.Schedule,
            modifier = modifier,
        )
    }
}

private val SpineWidth = 62.dp

@Preview(name = "Timeline", widthDp = 400, heightDp = 760)
@Composable
private fun MatchTimelinePreview() {
    KickoffTheme {
        MatchTimeline(
            entries = previewTimeline(),
            match = previewMatch(
                phase = MatchPhase.SECOND_HALF,
                elapsedMinutes = 67,
                score = Score(2, 1),
            ),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Preview(name = "Timeline - nothing yet", widthDp = 400)
@Composable
private fun MatchTimelineEmptyPreview() {
    KickoffTheme {
        MatchTimeline(entries = emptyList(), match = previewMatch())
    }
}
