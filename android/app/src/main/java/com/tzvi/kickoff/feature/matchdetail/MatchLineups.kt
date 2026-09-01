package com.tzvi.kickoff.feature.matchdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.TeamLineup
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.MetaChip
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import com.tzvi.kickoff.ui.theme.KickoffTheme

@Composable
internal fun MatchLineupsSection(
    lineups: MatchLineups?,
    match: Match,
    modifier: Modifier = Modifier,
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit = { _, _ -> },
) {
    val home = lineups?.home
    val away = lineups?.away
    if (home == null || away == null) {
        LineupsEmptyState(match, modifier)
        return
    }
    Column(modifier.fillMaxWidth()) {
        Pitch(home = home, away = away, onPlayerTap = onPlayerTap)
        Spacer(Modifier.height(20.dp))
        TeamLineupDetail(home, onPlayerTap = onPlayerTap)
        Spacer(Modifier.height(20.dp))
        TeamLineupDetail(away, onPlayerTap = onPlayerTap)
    }
}

/**
 * The starting XIs on an actual pitch.
 *
 * Everything drawn over the turf takes its colour from the pitch palette rather than from
 * the colour scheme: [com.tzvi.kickoff.ui.theme.KickoffAccents.pitch] is dark green in
 * both themes, so scheme colours would flip to dark-on-dark at night.
 */
@Composable
private fun Pitch(
    home: TeamLineup,
    away: TeamLineup,
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val turf = KickoffTheme.accents.pitch
    val markings = KickoffTheme.accents.pitchLine

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PitchAspect)
            .clip(KickoffShapes.medium)
            .background(turf),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawPitchMarkings(markings) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = PitchInset),
        ) {
            // The away side defends the top goal, so its rows run goalkeeper-first
            // downwards and its columns are mirrored: seen from this end their right
            // back stands on the viewer's left.
            HalfPitch(
                rows = away.rows.map { it.asReversed() },
                isHome = false,
                onTap = { onPlayerTap(it, away) },
                modifier = Modifier.weight(1f),
            )
            HalfPitch(
                rows = home.rows.asReversed(),
                isHome = true,
                onTap = { onPlayerTap(it, home) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HalfPitch(
    rows: List<List<LineupPlayer>>,
    isHome: Boolean,
    onTap: (LineupPlayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { player -> PlayerDisc(player, isHome, onTap = { onTap(player) }) }
            }
        }
    }
}

@Composable
private fun PlayerDisc(player: LineupPlayer, isHome: Boolean, onTap: () -> Unit) {
    val light = KickoffTheme.accents.onLive
    val turf = KickoffTheme.accents.pitch
    Column(
        modifier = Modifier
            .width(DiscColumnWidth)
            .clip(KickoffShapes.small)
            .clickable(onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The face fills the disc, with the shirt number riding its top-left corner -
        // the arrangement every scores app uses, because a photo identifies a player
        // faster than a number and the number disambiguates the photo.
        Box(contentAlignment = Alignment.TopStart) {
            Box(
                modifier = Modifier
                    .size(DiscSize)
                    .clip(KickoffShapeTokens.crest)
                    .background(light)
                    .border(1.5.dp, light, KickoffShapeTokens.crest),
                contentAlignment = Alignment.Center,
            ) {
                if (player.photoUrl != null) {
                    CrestImage(
                        url = player.photoUrl,
                        fallback = player.surname,
                        size = DiscSize,
                    )
                } else {
                    Text(
                        text = player.number?.toString().orEmpty(),
                        style = KickoffTextStyles.shirtNumber,
                        color = turf,
                    )
                }
            }
            if (player.photoUrl != null && player.number != null) {
                Text(
                    text = player.number.toString(),
                    style = KickoffTextStyles.shirtNumber,
                    color = light,
                    modifier = Modifier
                        .offset(x = (-4).dp, y = (-2).dp)
                        .clip(KickoffShapeTokens.crest)
                        .background(turf)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = player.surname,
            style = MaterialTheme.typography.labelSmall,
            color = light,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.drawPitchMarkings(color: Color) {
    val stroke = 1.5.dp.toPx()
    val inset = 10.dp.toPx()
    val width = size.width - inset * 2
    val height = size.height - inset * 2
    val outline = Stroke(stroke)

    drawRect(color, Offset(inset, inset), Size(width, height), style = outline)
    drawLine(
        color = color,
        start = Offset(inset, size.height / 2f),
        end = Offset(size.width - inset, size.height / 2f),
        strokeWidth = stroke,
    )
    drawCircle(color, width * 0.14f, center, style = outline)
    drawCircle(color, stroke * 1.4f, center)

    val boxWidth = width * 0.56f
    val boxHeight = height * 0.15f
    val boxLeft = inset + (width - boxWidth) / 2f
    drawRect(color, Offset(boxLeft, inset), Size(boxWidth, boxHeight), style = outline)
    drawRect(
        color = color,
        topLeft = Offset(boxLeft, size.height - inset - boxHeight),
        size = Size(boxWidth, boxHeight),
        style = outline,
    )

    val sixWidth = width * 0.26f
    val sixHeight = height * 0.055f
    val sixLeft = inset + (width - sixWidth) / 2f
    drawRect(color, Offset(sixLeft, inset), Size(sixWidth, sixHeight), style = outline)
    drawRect(
        color = color,
        topLeft = Offset(sixLeft, size.height - inset - sixHeight),
        size = Size(sixWidth, sixHeight),
        style = outline,
    )

    drawCircle(color, stroke * 1.4f, Offset(size.width / 2f, inset + boxHeight * 0.7f))
    drawCircle(
        color = color,
        radius = stroke * 1.4f,
        center = Offset(size.width / 2f, size.height - inset - boxHeight * 0.7f),
    )
}

@Composable
private fun TeamLineupDetail(
    lineup: TeamLineup,
    onPlayerTap: (LineupPlayer, TeamLineup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CrestImage(url = lineup.crestUrl, fallback = lineup.teamName, size = 26.dp)
            Text(
                text = lineup.teamName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            lineup.formation?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        }
        lineup.coachName?.takeIf { it.isNotBlank() }?.let { coach ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Coach · $coach",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lineup.substitutes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Substitutes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // A bench runs to a dozen names; scrolling the chips sideways keeps the
            // pitch above them on screen instead of pushing it off the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lineup.substitutes.forEach { player ->
                    SubstituteChip(player, onTap = { onPlayerTap(player, lineup) })
                }
            }
        }
    }
}

@Composable
private fun SubstituteChip(player: LineupPlayer, onTap: () -> Unit) {
    Surface(
        shape = KickoffShapeTokens.chip,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onTap,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = player.number?.toString() ?: "–",
                style = KickoffTextStyles.shirtNumber,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = player.surname,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LineupsEmptyState(match: Match, modifier: Modifier = Modifier) {
    when {
        match.phase == MatchPhase.SCHEDULED -> EmptyState(
            title = "Line-ups aren't out yet",
            body = "Line-ups usually land about an hour before kick-off. Pull down to " +
                "check again.",
            icon = Icons.Outlined.Groups,
            modifier = modifier,
        )
        match.phase == MatchPhase.OFF -> EmptyState(
            title = "No line-ups for this match",
            body = "The fixture was called off before either side named a team.",
            icon = Icons.Outlined.Groups,
            modifier = modifier,
        )
        else -> EmptyState(
            title = "No line-ups for this match",
            body = "The provider never published a starting XI for this fixture.",
            icon = Icons.Outlined.Groups,
            modifier = modifier,
        )
    }
}

/** Width over height: a real pitch is 68m by 105m, drawn end-on. */
private const val PitchAspect = 0.68f
private val PitchInset = 14.dp
private val DiscColumnWidth = 52.dp
private val DiscSize = 30.dp

@Preview(name = "Line-ups", widthDp = 400, heightDp = 1100)
@Composable
private fun MatchLineupsPreview() {
    KickoffTheme {
        MatchLineupsSection(
            lineups = previewLineups(),
            match = previewMatch(phase = MatchPhase.SECOND_HALF, elapsedMinutes = 67),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Preview(name = "Line-ups - not out yet", widthDp = 400)
@Composable
private fun MatchLineupsEmptyPreview() {
    KickoffTheme {
        MatchLineupsSection(lineups = null, match = previewMatch())
    }
}
