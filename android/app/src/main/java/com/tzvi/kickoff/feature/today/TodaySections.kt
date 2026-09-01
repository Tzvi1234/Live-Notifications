package com.tzvi.kickoff.feature.today

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.TeamCrest
import com.tzvi.kickoff.ui.motion.TransformKeys
import com.tzvi.kickoff.ui.motion.containerTransform
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The day divider inside "Next up". Opaque, because it sticks over the cards below it. */
@Composable
internal fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/**
 * One diary entry, sized to sit between two match cards without competing with them.
 *
 * The leading bar is the owning calendar's own colour, which is the only way a user
 * with four synced calendars can tell whose meeting this is at a glance.
 */
@Composable
internal fun CalendarEventRow(
    event: CalendarEvent,
    modifier: Modifier = Modifier,
) {
    val accent = if (event.color != 0) Color(event.color) else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(CalendarBarWidth)
                .height(CalendarBarHeight)
                .clip(KickoffShapeTokens.pill)
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = event.timeLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(CalendarTimeWidth),
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val location = event.location
            if (location != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "09:30", or "All day" for an event that has no meaningful clock time. */
private fun CalendarEvent.timeLabel(): String =
    if (isAllDay) "All day"
    else EVENT_TIME_FORMAT.format(instanceStart.atZone(ZoneId.systemDefault()))

@Composable
internal fun FavouriteTeamsRow(
    teams: List<Team>,
    onOpenTeams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(teams, key = { it.id }) { team ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(TeamChipWidth)
                    .clip(KickoffShapes.medium)
                    .clickable(onClick = onOpenTeams)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                TeamCrest(team, size = CrestSize, shared = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = team.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun NoTeamsState(onOpenTeams: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = "No teams followed yet",
        body = "Pick a few clubs and matchUP will keep their fixtures, scores and " +
            "line-ups on this screen.",
        icon = Icons.Outlined.Groups,
        actionLabel = "Choose teams",
        onAction = onOpenTeams,
        modifier = modifier,
    )
}

@Composable
internal fun CalendarPermissionState(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = "Calendar access is off",
        body = "Calendar sync is switched on, but Android has not granted matchUP " +
            "permission to read your events yet.",
        icon = Icons.Outlined.CalendarMonth,
        actionLabel = "Allow calendar access",
        onAction = onRequestPermission,
        modifier = modifier,
    )
}

/** No source configured at all - the one failure the user can actually fix themselves. */
@Composable
internal fun NoSourceBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "No football data source",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Point matchUP at a backend, or paste an API-Football key, and " +
                "fixtures will start arriving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Open settings", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** A refresh that failed for any other reason. The cached list underneath still stands. */
@Composable
internal fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
        TextButton(onClick = onDismiss) { Text("Hide") }
    }
}

/** The button's own container is the origin of the transform into the settings screen. */
@Composable
internal fun SettingsAction(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onOpenSettings,
        modifier = modifier.containerTransform(TransformKeys.SETTINGS, KickoffShapeTokens.pill),
    ) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val CalendarBarWidth = 4.dp
private val CalendarBarHeight = 34.dp
private val CalendarTimeWidth = 46.dp
private val TeamChipWidth = 72.dp
private val CrestSize = 44.dp

private val EVENT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Preview
@Composable
private fun CalendarEventRowPreview() {
    KickoffTheme {
        CalendarEventRow(
            event = CalendarEvent(
                eventId = 1,
                instanceStart = Instant.now().plus(2, ChronoUnit.HOURS),
                instanceEnd = Instant.now().plus(3, ChronoUnit.HOURS),
                title = "Design review",
                location = "Meeting room 3",
                description = null,
                isAllDay = false,
                calendarId = 1,
                calendarName = "Work",
                accountName = null,
                color = 0xFF3F51B5.toInt(),
            ),
        )
    }
}

@Preview
@Composable
private fun NoSourceBannerPreview() {
    KickoffTheme { NoSourceBanner(onOpenSettings = {}) }
}
