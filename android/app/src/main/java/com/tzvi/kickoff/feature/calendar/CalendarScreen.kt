package com.tzvi.kickoff.feature.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.CalendarAvailability
import com.tzvi.kickoff.ui.component.LoadingState
import com.tzvi.kickoff.ui.component.SectionHeader
import com.tzvi.kickoff.ui.component.SettingsRow
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen() {
    val viewModel: CalendarViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    // Only ever re-reads the grant, so access given in system settings shows up when the
    // user comes back. Nothing is requested here: a dialog nobody asked for gets denied,
    // and the second denial is the one that silences it for good.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The rationale flag going false right after a refusal is the only signal Android
        // gives that it has stopped showing the dialog at all.
        val canAskAgain = activity
            ?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) == true
        viewModel.onPermissionResult(granted = granted, canAskAgain = canAskAgain)
    }

    CalendarContent(
        state = state,
        onSelectDate = viewModel::selectDate,
        onPreviousMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onToday = viewModel::showToday,
        onRequestPermission = {
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        },
        onOpenAppSettings = { context.openAppSettings() },
        onSetSyncEnabled = viewModel::setSyncEnabled,
        onToggleCalendar = viewModel::setCalendarEnabled,
        onSetLeadMinutes = viewModel::setLeadMinutes,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarContent(
    state: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSetSyncEnabled: (Boolean) -> Unit,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onSetLeadMinutes: (Int) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Calendar") },
                actions = {
                    AnimatedVisibility(
                        visible = !state.isOnCurrentMonth,
                        enter = fadeIn(Motion.effects(Motion.Duration.MEDIUM)),
                        exit = fadeOut(Motion.effects(Motion.Duration.SHORT)),
                    ) {
                        TextButton(onClick = onToday) { Text("Today") }
                    }
                },
            )
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            when {
                state.isLoading -> LoadingState(label = "Reading your calendars")

                // Without the permission there is no grid to draw and no calendar to list,
                // so the wall is the whole screen rather than a banner on top of one.
                state.permissionDenied -> CalendarPermissionGate(
                    canRequestPermission = state.canRequestPermission,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )

                else -> CalendarBody(
                    state = state,
                    onSelectDate = onSelectDate,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onSetSyncEnabled = onSetSyncEnabled,
                    onToggleCalendar = onToggleCalendar,
                    onSetLeadMinutes = onSetLeadMinutes,
                )
            }
        }
    }
}

@Composable
private fun CalendarBody(
    state: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSetSyncEnabled: (Boolean) -> Unit,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onSetLeadMinutes: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            bottom = BottomPadding,
        ),
    ) {
        item(key = "month") {
            Column {
                MonthNavigator(
                    grid = state.grid,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                )
                WeekdayHeader(state.weekdayLabels)
                AnimatedContent(
                    targetState = state.grid,
                    contentKey = { it.month },
                    transitionSpec = { monthTransition(targetState.month > initialState.month) },
                    label = "month-grid",
                ) { grid ->
                    MonthGridView(
                        grid = grid,
                        selectedDate = state.selectedDate,
                        onSelectDate = onSelectDate,
                    )
                }
            }
        }

        item(key = "day-header") {
            SelectedDayHeader(
                title = state.selectedDayTitle,
                dateLabel = state.selectedDateLabel,
                eventCount = state.agenda.size,
                isLoading = state.isAgendaLoading,
                modifier = Modifier.padding(
                    start = RowPaddingHorizontal,
                    end = RowPaddingHorizontal,
                    top = SectionGap,
                    bottom = ItemGap,
                ),
            )
        }

        val reason = state.emptyReason
        when {
            state.isAgendaLoading -> item(key = "agenda-loading") { AgendaLoading() }

            reason != null -> item(key = "agenda-empty") {
                AgendaEmptyState(
                    reason = reason,
                    dayPhrase = state.selectedDayPhrase,
                    canRequestPermission = state.canRequestPermission,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }

            else -> items(state.agenda, key = { it.id }) { entry ->
                AgendaRow(
                    entry = entry,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                            placementSpec = Motion.offsetSpring(),
                            fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                        )
                        .padding(bottom = ItemGap),
                )
            }
        }

        item(key = "live-card-header") {
            SectionHeader(title = "Live card", modifier = Modifier.padding(top = SectionGap))
        }

        item(key = "sync-switch") {
            SettingsRow(
                title = "Live card for events",
                subtitle = "Raise a card on the lock screen shortly before an event starts",
                trailing = {
                    Switch(checked = state.syncEnabled, onCheckedChange = onSetSyncEnabled)
                },
                onClick = { onSetSyncEnabled(!state.syncEnabled) },
            )
        }

        item(key = "lead") {
            LeadTimeSection(
                leadMinutes = state.leadMinutes,
                enabled = state.syncEnabled,
                onSelect = onSetLeadMinutes,
                modifier = Modifier.padding(
                    horizontal = RowPaddingHorizontal,
                    vertical = ItemGap,
                ),
            )
        }

        item(key = "calendars-header") {
            Column(Modifier.padding(top = SectionGap)) {
                SectionHeader(title = "Calendars")
                Text(
                    text = "Which calendars matchUP reads - both for this agenda and for " +
                        "the live card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = RowPaddingHorizontal,
                        vertical = ItemGap,
                    ),
                )
            }
        }

        items(state.calendars, key = { "calendar-${it.id}" }) { toggle ->
            CalendarToggleRow(
                toggle = toggle,
                onToggle = onToggleCalendar,
                modifier = Modifier.animateItem(
                    fadeInSpec = Motion.effects(Motion.Duration.SHORT),
                    placementSpec = Motion.offsetSpring(),
                    fadeOutSpec = Motion.effects(Motion.Duration.SHORT),
                ),
            )
        }

        item(key = "tail") { Spacer(Modifier.height(SectionGap)) }
    }
}

/** The one place a user can turn a twice-denied permission back on. */
private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private val ScreenPadding = 16.dp
private val ItemGap = 8.dp
private val SectionGap = 12.dp
private val BottomPadding = 40.dp

// ---- previews ---------------------------------------------------------------------

private fun previewState(
    availability: CalendarAvailability = CalendarAvailability.OK,
    agenda: List<AgendaEntry> = emptyList(),
    calendars: List<CalendarToggle> = previewCalendars(),
    isLoading: Boolean = false,
    canRequestPermission: Boolean = true,
    syncEnabled: Boolean = true,
): CalendarUiState {
    val today = LocalDate.of(2026, 8, 31)
    val month = YearMonth.from(today)
    return CalendarUiState(
        isLoading = isLoading,
        availability = availability,
        canRequestPermission = canRequestPermission,
        grid = buildMonthGrid(
            month = month,
            today = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            dots = mapOf(
                today to listOf(PreviewBlue),
                today.minusDays(3) to listOf(PreviewGreen, PreviewRed),
                today.plusDays(2) to listOf(PreviewGreen),
            ),
        ),
        weekdayLabels = weekdayLabels(DayOfWeek.MONDAY),
        isOnCurrentMonth = true,
        selectedDate = today,
        selectedDayTitle = "Today",
        selectedDateLabel = "Monday 31 August",
        selectedDayPhrase = "today",
        agenda = agenda,
        calendars = calendars,
        syncEnabled = syncEnabled,
        leadMinutes = 30,
    )
}

private fun previewCalendars() = listOf(
    CalendarToggle(
        id = 1,
        name = "Personal",
        accountName = "tzvi@example.com",
        color = PreviewBlue,
        isEnabled = true,
        isHidden = false,
    ),
    CalendarToggle(
        id = 2,
        name = "Work",
        accountName = "tzvi@work.example",
        color = PreviewGreen,
        isEnabled = true,
        isHidden = false,
    ),
    CalendarToggle(
        id = 3,
        name = "Birthdays",
        accountName = "tzvi@example.com",
        color = PreviewRed,
        isEnabled = false,
        isHidden = true,
    ),
)

private fun previewAgenda() = listOf(
    AgendaEntry(
        id = "1",
        timeLabel = "All day",
        endLabel = null,
        title = "Matchday trip to London",
        location = null,
        calendarName = "Personal",
        color = PreviewBlue,
    ),
    AgendaEntry(
        id = "2",
        timeLabel = "09:30",
        endLabel = "10:15",
        title = "Standup",
        location = "Meet - Room 2",
        calendarName = "Work",
        color = PreviewGreen,
    ),
    AgendaEntry(
        id = "3",
        timeLabel = "19:45",
        endLabel = "21:45",
        title = "Arsenal v Chelsea",
        location = "Emirates Stadium",
        calendarName = "Personal",
        color = PreviewBlue,
    ),
)

// Stand-ins for the ARGB values the provider hands back for a real calendar.
private val PreviewBlue = 0xFF3F51B5.toInt()
private val PreviewGreen = 0xFF00A344.toInt()
private val PreviewRed = 0xFFD32F2F.toInt()

@Composable
private fun CalendarPreview(state: CalendarUiState) {
    KickoffTheme {
        CalendarContent(
            state = state,
            onSelectDate = {},
            onPreviousMonth = {},
            onNextMonth = {},
            onToday = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onSetSyncEnabled = {},
            onToggleCalendar = { _, _ -> },
            onSetLeadMinutes = {},
        )
    }
}

@Preview(name = "Calendar - agenda", heightDp = 1000)
@Composable
private fun CalendarAgendaPreview() {
    CalendarPreview(previewState(agenda = previewAgenda()))
}

@Preview(name = "Calendar - nothing scheduled", heightDp = 1000)
@Composable
private fun CalendarEmptyDayPreview() {
    CalendarPreview(previewState())
}

@Preview(name = "Calendar - permission", heightDp = 900)
@Composable
private fun CalendarPermissionPreview() {
    CalendarPreview(
        previewState(
            availability = CalendarAvailability.PERMISSION_DENIED,
            calendars = emptyList(),
        ),
    )
}

@Preview(name = "Calendar - permission blocked", heightDp = 900)
@Composable
private fun CalendarPermissionBlockedPreview() {
    CalendarPreview(
        previewState(
            availability = CalendarAvailability.PERMISSION_DENIED,
            calendars = emptyList(),
            canRequestPermission = false,
        ),
    )
}

@Preview(name = "Calendar - all hidden", heightDp = 1000)
@Composable
private fun CalendarHiddenPreview() {
    CalendarPreview(previewState(availability = CalendarAvailability.NO_VISIBLE_CALENDARS))
}

@Preview(name = "Calendar - loading", heightDp = 900)
@Composable
private fun CalendarLoadingPreview() {
    CalendarPreview(previewState(isLoading = true))
}
