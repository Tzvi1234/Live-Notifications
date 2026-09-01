package com.tzvi.kickoff.feature.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import java.time.LocalDate

/** The month title and the arrows either side of it. */
@Composable
internal fun MonthNavigator(
    grid: MonthGrid,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
        }
        AnimatedContent(
            targetState = grid,
            contentKey = { it.month },
            transitionSpec = { monthTransition(targetState.month > initialState.month) },
            label = "month-title",
            modifier = Modifier.weight(1f),
        ) { shown ->
            Text(
                text = shown.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onNextMonth) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
internal fun WeekdayHeader(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The month itself: a plain column of week rows.
 *
 * Deliberately not a lazy grid - six rows never need recycling, and a lazy container here
 * would fight the scroll of the agenda it sits above.
 */
@Composable
internal fun MonthGridView(
    grid: MonthGrid,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        grid.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        selected = day.date == selectedDate,
                        onClick = { onSelectDate(day.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            day.isToday -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = Motion.effects(Motion.Duration.SHORT),
        label = "day-container",
    )
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        day.isToday -> MaterialTheme.colorScheme.primary
        day.inMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = OutOfMonthAlpha)
    }

    Column(
        modifier = modifier
            .padding(CellGap)
            .height(CellHeight)
            .clip(KickoffShapes.small)
            .background(container)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
            color = content,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        // The dot row keeps its height whether or not there are dots, so a day gaining an
        // event does not nudge its number upwards.
        Row(
            modifier = Modifier.height(DotSize),
            horizontalArrangement = Arrangement.spacedBy(DotGap),
        ) {
            day.dotColors.forEach { argb ->
                Box(
                    Modifier
                        .size(DotSize)
                        .clip(KickoffShapeTokens.crest)
                        .background(dotColor(argb, selected)),
                )
            }
        }
    }
}

/**
 * The owning calendar's own colour, which is the only way a user with four synced
 * calendars can tell whose day this is. On the selected day the container is already
 * `primary`, where an arbitrary provider colour can disappear entirely.
 */
@Composable
private fun dotColor(argb: Int, selected: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.onPrimary
    argb != 0 -> Color(argb)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun SelectedDayHeader(
    title: String,
    dateLabel: String,
    eventCount: Int,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val subtitle = listOfNotNull(
        dateLabel.takeIf { it != title },
        // A day whose events have not been read yet has a count of zero, and announcing
        // "Nothing scheduled" directly above the loader that is about to contradict it
        // is worse than saying nothing.
        eventCountLabel(eventCount).takeUnless { isLoading },
    ).joinToString(" · ")

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One entry in the day's agenda.
 *
 * The times are already formatted: an all-day event is stored at midnight UTC and would
 * be a day out if this row rendered its instant in the device's zone.
 */
@Composable
internal fun AgendaRow(entry: AgendaEntry, modifier: Modifier = Modifier) {
    val accent = if (entry.color != 0) Color(entry.color) else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = RowPaddingHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(BarWidth)
                .height(BarHeight)
                .clip(KickoffShapeTokens.pill)
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(TimeColumnWidth)) {
            Text(
                text = entry.timeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val end = entry.endLabel
            if (end != null) {
                Text(
                    text = end,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val location = entry.location
            if (location != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val calendar = entry.calendarName
            if (calendar != null) {
                Text(
                    text = calendar,
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
internal fun AgendaLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AgendaLoaderHeight),
        contentAlignment = Alignment.Center,
    ) {
        KickoffLoader(size = 36.dp)
    }
}

/** Each empty reason gets its own copy, because each one has its own fix. */
@Composable
internal fun AgendaEmptyState(
    reason: CalendarEmptyReason,
    dayPhrase: String,
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (reason) {
        CalendarEmptyReason.PERMISSION_DENIED -> CalendarPermissionGate(
            canRequestPermission = canRequestPermission,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            modifier = modifier,
        )

        CalendarEmptyReason.PROVIDER_UNAVAILABLE -> EmptyState(
            title = "No calendar provider",
            body = "matchUP asked Android for the calendar database and got nothing back. " +
                "Some builds ship without one; installing a calendar app that syncs into " +
                "the system provider gives matchUP something to read.",
            icon = Icons.Outlined.CloudOff,
            modifier = modifier,
        )

        CalendarEmptyReason.NO_CALENDARS -> EmptyState(
            title = "No calendars on this device",
            body = "matchUP can read your calendars, there just aren't any yet. Add or " +
                "sync one in your calendar app and it will show up here.",
            icon = Icons.Outlined.CalendarMonth,
            modifier = modifier,
        )

        CalendarEmptyReason.NO_VISIBLE_CALENDARS -> EmptyState(
            title = "Every calendar is hidden",
            body = "This device has calendars, but all of them are switched off for " +
                "display in your calendar app. Turn one back on there and its events " +
                "will appear here.",
            icon = Icons.Outlined.VisibilityOff,
            modifier = modifier,
        )

        CalendarEmptyReason.NONE_SELECTED -> EmptyState(
            title = "No calendars selected",
            body = "Every calendar below is switched off for matchUP. Turn one on and " +
                "its events fill this agenda - and its live cards come back with it.",
            icon = Icons.Outlined.EventBusy,
            modifier = modifier,
        )

        CalendarEmptyReason.NOTHING_SCHEDULED -> EmptyState(
            title = "Nothing scheduled $dayPhrase",
            body = "Your calendars are being read; there is simply no event $dayPhrase. " +
                "Days with something on carry a dot in the grid above.",
            icon = Icons.Outlined.EventAvailable,
            modifier = modifier,
        )
    }
}

/**
 * The permission wall.
 *
 * The copy says exactly what is read and where it goes, because "allow calendar access"
 * on its own is a request to trust an app with a diary.
 */
@Composable
internal fun CalendarPermissionGate(
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = "matchUP can't see your calendar",
        body = if (canRequestPermission) {
            "matchUP reads the events already on this device so it can raise a live card " +
                "shortly before one starts, exactly as it does for a match. It only ever " +
                "reads, it never writes to a calendar, and nothing it reads leaves the phone."
        } else {
            "Android stops offering the permission dialog after a second refusal, so " +
                "calendar access can now only be turned on from matchUP's own app settings. " +
                "It stays read-only, and nothing it reads leaves the phone."
        },
        icon = Icons.Outlined.CalendarMonth,
        actionLabel = if (canRequestPermission) "Allow calendar access" else "Open app settings",
        onAction = if (canRequestPermission) onRequestPermission else onOpenAppSettings,
        modifier = modifier,
    )
}

@Composable
internal fun CalendarToggleRow(
    toggle: CalendarToggle,
    onToggle: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (toggle.color != 0) Color(toggle.color) else MaterialTheme.colorScheme.primary
    val subtitle = listOfNotNull(
        toggle.accountName.takeIf { it.isNotBlank() && it != toggle.name },
        "Hidden in your calendar app".takeIf { toggle.isHidden },
    ).joinToString(" · ")

    Row(
        // The row is the switch, rather than a clickable row that happens to contain one:
        // two targets for one setting makes a screen reader announce it twice.
        modifier = modifier
            .fillMaxWidth()
            .clip(KickoffShapes.medium)
            .toggleable(
                value = toggle.isEnabled,
                role = Role.Switch,
                onValueChange = { checked -> onToggle(toggle.id, checked) },
            )
            .padding(horizontal = RowPaddingHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(SwatchSize)
                .clip(KickoffShapeTokens.crest)
                .background(accent),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = toggle.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = toggle.isEnabled, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeadTimeSection(
    leadMinutes: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "The live card appears ${leadPhrase(leadMinutes)} before an event starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LeadTimeOptions.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = minutes == leadMinutes,
                    onClick = { onSelect(minutes) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, LeadTimeOptions.size),
                    label = { Text(leadLabel(minutes), maxLines = 1) },
                )
            }
        }
    }
}

/** Forward brings the incoming month in from the right, back from the left. */
internal fun monthTransition(forward: Boolean): ContentTransform {
    val enter = slideInHorizontally(Motion.offsetSpring()) { width ->
        if (forward) width else -width
    } + fadeIn(Motion.effects(Motion.Duration.SHORT))
    val exit = slideOutHorizontally(Motion.offsetSpring()) { width ->
        if (forward) -width else width
    } + fadeOut(Motion.effects(Motion.Duration.SHORT))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        // Both grids are six rows tall, so the container must not resize under them.
        sizeTransform = SizeTransform(clip = false),
    )
}

private fun eventCountLabel(count: Int): String = when (count) {
    0 -> "Nothing scheduled"
    1 -> "1 event"
    else -> "$count events"
}

private fun leadLabel(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

private fun leadPhrase(minutes: Int): String = when {
    minutes % 60 != 0 -> "$minutes minutes"
    minutes == 60 -> "an hour"
    else -> "${minutes / 60} hours"
}

private const val OutOfMonthAlpha = 0.45f

/** Shared with the screen so every row in this feature sits on the same left edge. */
internal val RowPaddingHorizontal = 12.dp

private val CellHeight = 42.dp
private val CellGap = 2.dp
private val DotSize = 5.dp
private val DotGap = 3.dp
private val BarWidth = 4.dp
private val BarHeight = 36.dp
private val TimeColumnWidth = 56.dp
private val SwatchSize = 14.dp
private val AgendaLoaderHeight = 120.dp
