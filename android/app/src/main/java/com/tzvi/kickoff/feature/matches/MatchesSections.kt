package com.tzvi.kickoff.feature.matches

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.LocalDate

/**
 * The date strip.
 *
 * It opens already showing the selected date rather than scrolling to it: an animated
 * scroll on first composition reads as the screen being unfinished when it appears.
 */
@Composable
internal fun WeekStrip(
    days: List<DayChip>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = remember(days, selectedDate) {
        days.indexOfFirst { it.date == selectedDate }
    }
    var hasPositioned by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val target = (selectedIndex - CHIPS_LEADING_SELECTION).coerceAtLeast(0)
        if (hasPositioned) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        hasPositioned = true
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
    ) {
        items(days, key = { it.date.toEpochDay() }) { day ->
            DayChipItem(
                day = day,
                selected = day.date == selectedDate,
                onClick = { onSelectDate(day.date) },
            )
        }
    }
}

@Composable
private fun DayChipItem(day: DayChip, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            day.isToday -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = Motion.effects(Motion.Duration.SHORT),
        label = "day-chip-container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = Motion.effects(Motion.Duration.SHORT),
        label = "day-chip-content",
    )

    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.width(ChipWidth),
        shape = KickoffShapes.small,
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(vertical = ChipVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = day.weekday,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = day.dayOfMonth,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            // Always laid out, only sometimes painted, so today's marker cannot change
            // the height of the chip it sits in.
            Box(Modifier.size(TodayDotSize)) {
                if (day.isToday) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(KickoffShapeTokens.crest)
                            .background(if (selected) content else MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchFilterRow(
    selected: MatchFilter,
    onSelect: (MatchFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = MatchFilter.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(option.label, maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun CompetitionHeader(group: CompetitionGroup, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrestImage(
            url = group.leagueLogoUrl,
            fallback = group.leagueName,
            size = LeagueLogoSize,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.leagueName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val round = group.round
            if (round != null) {
                Text(
                    text = round,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A refresh that failed on top of fixtures we already hold: a banner, not a whole page. */
@Composable
internal fun StaleFixturesBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KickoffShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$message Showing saved fixtures.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
internal fun FixturesEmptyState(
    reason: MatchesEmptyReason,
    state: MatchesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (reason) {
        MatchesEmptyReason.NO_SOURCE -> EmptyState(
            title = "No football source yet",
            body = "Kickoff isn't pointed at any data. Add your backend URL or an " +
                "API-Football key in Settings and the schedule will fill itself in.",
            icon = Icons.Outlined.CloudOff,
            modifier = modifier,
        )

        MatchesEmptyReason.LOAD_FAILED -> EmptyState(
            title = "Couldn't load these fixtures",
            body = state.errorMessage ?: "The fixture list didn't come back this time.",
            icon = Icons.Outlined.WifiOff,
            actionLabel = "Try again",
            onAction = onRetry,
            modifier = modifier,
        )

        MatchesEmptyReason.NO_FIXTURES -> EmptyState(
            title = "Nothing scheduled ${state.dateLabel}",
            body = "No competition Kickoff can see has a fixture on this date. " +
                "Pick another day on the strip above.",
            icon = Icons.Outlined.EventBusy,
            modifier = modifier,
        )

        MatchesEmptyReason.NOTHING_LIVE -> EmptyState(
            title = "Nothing in play",
            body = "None of the ${state.dayMatchCount} fixtures ${state.dateLabel} is " +
                "under way right now. Switch to All for the full schedule.",
            icon = Icons.Outlined.SportsSoccer,
            modifier = modifier,
        )

        MatchesEmptyReason.NO_FOLLOWED_TEAMS -> EmptyState(
            title = "You don't follow any teams yet",
            body = "Follow a few clubs from the Teams tab and their fixtures will be " +
                "one tap away here.",
            icon = Icons.Outlined.Groups,
            modifier = modifier,
        )

        MatchesEmptyReason.NO_TEAM_FIXTURES -> EmptyState(
            title = "None of your teams play ${state.dateLabel}",
            body = "There are ${state.dayMatchCount} other fixtures on this date. " +
                "Switch to All to see them.",
            icon = Icons.Outlined.Groups,
            modifier = modifier,
        )
    }
}

internal val ScreenPadding = 16.dp
internal val ItemGap = 10.dp
internal val SectionGap = 12.dp

private val ChipWidth = 54.dp
private val ChipGap = 8.dp
private val ChipVerticalPadding = 10.dp
private val TodayDotSize = 4.dp
private val LeagueLogoSize = 22.dp

/** Two chips of context to the left keeps the selection off the leading edge. */
private const val CHIPS_LEADING_SELECTION = 2

@Preview(name = "Week strip")
@Composable
private fun WeekStripPreview() {
    val today = LocalDate.now()
    KickoffTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                WeekStrip(
                    days = (-2L..6L).map { offset ->
                        val date = today.plusDays(offset)
                        DayChip(
                            date = date,
                            weekday = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                            dayOfMonth = date.dayOfMonth.toString(),
                            isToday = offset == 0L,
                        )
                    },
                    selectedDate = today.plusDays(1),
                    onSelectDate = {},
                )
                Spacer(Modifier.height(12.dp))
                MatchFilterRow(
                    selected = MatchFilter.MY_TEAMS,
                    onSelect = {},
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }
    }
}

@Preview(name = "Empty - nothing live")
@Composable
private fun NothingLivePreview() {
    KickoffTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            FixturesEmptyState(
                reason = MatchesEmptyReason.NOTHING_LIVE,
                state = MatchesUiState(
                    isLoading = false,
                    dateLabel = "today",
                    dayMatchCount = 8,
                    filter = MatchFilter.LIVE,
                ),
                onRetry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
