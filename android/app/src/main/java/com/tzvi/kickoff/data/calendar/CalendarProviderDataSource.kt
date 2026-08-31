package com.tzvi.kickoff.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.tzvi.kickoff.core.model.CalendarAvailability
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.DeviceCalendar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the device's calendars through `CalendarContract`.
 *
 * This is deliberately *not* the Google Calendar REST API. The provider already holds
 * every synced Google calendar - plus Exchange, CalDAV and local ones - needs no OAuth
 * client, no consent screen and no sensitive-scope verification, works offline, and
 * hands back recurrences already expanded. Nothing read here ever leaves the device.
 */
@Singleton
class CalendarProviderDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun availability(): CalendarAvailability = withContext(io) {
        if (!hasPermission()) return@withContext CalendarAvailability.PERMISSION_DENIED
        val calendars = runCatching { calendars() }.getOrNull()
            ?: return@withContext CalendarAvailability.PROVIDER_UNAVAILABLE
        when {
            calendars.isEmpty() -> CalendarAvailability.NO_CALENDARS
            calendars.none { it.isVisible } -> CalendarAvailability.NO_VISIBLE_CALENDARS
            else -> CalendarAvailability.OK
        }
    }

    suspend fun calendars(): List<DeviceCalendar> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        // The cursor really can be null: some builds ship without a calendar provider.
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DeviceCalendar(
                            id = cursor.getLong(0),
                            accountName = cursor.getString(1).orEmpty(),
                            accountType = cursor.getString(2).orEmpty(),
                            displayName = cursor.getString(3).orEmpty(),
                            color = cursor.getInt(4),
                            isVisible = cursor.getInt(5) == 1,
                            isSyncing = cursor.getInt(6) == 1,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /**
     * Occurrences between [from] and [to], read from `Instances` rather than `Events`
     * so recurring meetings come back already expanded.
     */
    suspend fun instances(
        from: Instant,
        to: Instant,
        calendarIds: Set<Long> = emptySet(),
    ): List<CalendarEvent> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()

        // The Instances URI carries the window in the path, begin first then end.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()

        val selection = buildString {
            append("${CalendarContract.Instances.VISIBLE} = 1")
            append(" AND (${CalendarContract.Instances.STATUS} IS NULL")
            append(" OR ${CalendarContract.Instances.STATUS} != ")
            append(CalendarContract.Instances.STATUS_CANCELED)
            append(")")
        }

        resolver.query(
            uri,
            INSTANCE_PROJECTION,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val calendarId = cursor.getLong(7)
                    if (calendarIds.isNotEmpty() && calendarId !in calendarIds) continue
                    add(
                        CalendarEvent(
                            eventId = cursor.getLong(0),
                            instanceStart = Instant.ofEpochMilli(cursor.getLong(1)),
                            instanceEnd = Instant.ofEpochMilli(cursor.getLong(2)),
                            title = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: "(No title)",
                            location = cursor.getString(4)?.takeIf { it.isNotBlank() },
                            description = cursor.getString(5)?.takeIf { it.isNotBlank() },
                            isAllDay = cursor.getInt(6) == 1,
                            calendarId = calendarId,
                            calendarName = cursor.getString(8),
                            accountName = null,
                            color = cursor.getInt(9),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /**
     * Emits whenever the provider changes.
     *
     * A single account sync fires `onChange` dozens of times in a burst, so this is
     * debounced hard; the observer also dies with the process, which is why the
     * scheduled worker - not this - is the backbone of the calendar pipeline.
     */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.debounce(2.seconds)

    private companion object {
        // Instances inherits the Events columns, which do NOT include ACCOUNT_NAME -
        // querying for it throws. The owning account is resolved from the Calendars
        // table via CALENDAR_ID instead; see CalendarRepository.
        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,              // 0
            CalendarContract.Instances.BEGIN,                 // 1
            CalendarContract.Instances.END,                   // 2
            CalendarContract.Instances.TITLE,                 // 3
            CalendarContract.Instances.EVENT_LOCATION,        // 4
            CalendarContract.Instances.DESCRIPTION,           // 5
            CalendarContract.Instances.ALL_DAY,               // 6
            CalendarContract.Instances.CALENDAR_ID,           // 7
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, // 8
            CalendarContract.Instances.CALENDAR_COLOR,        // 9
        )
    }
}
