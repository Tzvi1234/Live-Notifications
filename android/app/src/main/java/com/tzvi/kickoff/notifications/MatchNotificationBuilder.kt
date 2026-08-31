package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tzvi.kickoff.MainActivity
import com.tzvi.kickoff.R
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.MatchStatistics
import com.tzvi.kickoff.core.model.TeamLineup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a [LiveActivity.MatchActivity] into a notification.
 *
 * Android forces a choice that iOS does not. A promoted "Live Update" is the only
 * notification that reaches the status-bar chip, stays expanded on the lock screen and
 * can appear on an always-on display - but the eligibility rules forbid custom
 * `RemoteViews` outright, so the promoted card can only be the system template plus a
 * `ProgressStyle` bar. A custom scoreboard with crests and a big scoreline is prettier
 * in the shade, and is permanently disqualified from all of those surfaces.
 *
 * So this builds both and picks at post time; see [Rendering].
 */
@Singleton
class MatchNotificationBuilder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capability: LiveUpdateCapability,
) {
    /** Which of the three faces a given post used, so callers can report it honestly. */
    enum class Rendering {
        /** ProgressStyle + promoted ongoing. Reaches the chip, lock screen and AOD. */
        PROMOTED,

        /** Custom scoreboard RemoteViews. Shade and lock screen only, never AOD. */
        RICH,

        /** System template. The fallback that always works. */
        PLAIN,
    }

    data class Result(val notification: Notification, val rendering: Rendering)

    data class Crests(val home: Bitmap, val away: Bitmap, val league: Bitmap?)

    fun build(
        activity: LiveActivity.MatchActivity,
        crests: Crests?,
        style: LiveCardStyle,
        alerting: Boolean = false,
    ): Result {
        val rendering = chooseRendering(style, crests)
        val builder = baseBuilder(activity, alerting)

        when (rendering) {
            Rendering.PROMOTED -> applyPromoted(builder, activity, crests)
            Rendering.RICH -> applyRich(builder, activity, requireNotNull(crests))
            Rendering.PLAIN -> applyPlain(builder, activity)
        }
        return Result(builder.build(), rendering)
    }

    private fun chooseRendering(style: LiveCardStyle, crests: Crests?): Rendering = when (style) {
        LiveCardStyle.PLAIN -> Rendering.PLAIN
        LiveCardStyle.RICH -> if (crests != null) Rendering.RICH else Rendering.PLAIN
        LiveCardStyle.AUTO -> when {
            // Promotion is what buys the chip, the lock screen and the AOD, so it wins
            // whenever the device and the user allow it.
            capability.supportsProgressStyle && capability.canPostPromoted() -> Rendering.PROMOTED
            crests != null -> Rendering.RICH
            else -> Rendering.PLAIN
        }
    }

    // ---- shared scaffolding --------------------------------------------------

    private fun baseBuilder(
        activity: LiveActivity.MatchActivity,
        alerting: Boolean,
    ): NotificationCompat.Builder {
        val match = activity.match
        val channel = if (alerting) NotificationChannels.MATCH_EVENTS else NotificationChannels.LIVE_MATCH

        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_kickoff)
            .setContentTitle(contentTitle(activity))
            .setContentText(contentText(activity))
            .setSubText(match.leagueName.takeIf { it.isNotBlank() })
            .setContentIntent(openMatchIntent(match.id))
            .setDeleteIntent(dismissIntent(activity.key))
            .setOngoing(activity.stage != LiveActivity.MatchActivity.Stage.FULL_TIME)
            .setOnlyAlertOnce(!alerting)
            .setSilent(!alerting)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            // setColorized(true) would disqualify promotion, and the docs' own
            // ProgressStyle sample sets it - do not copy that line.
            .setShowWhen(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_close,
                    context.getString(R.string.action_stop_following),
                    stopFollowingIntent(activity.key, match.id),
                ).build(),
            )
    }

    private fun contentTitle(activity: LiveActivity.MatchActivity): String {
        val m = activity.match
        return when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH ->
                "${m.home.name} vs ${m.away.name}"
            else -> {
                val score = m.score ?: return "${m.home.name} vs ${m.away.name}"
                "${m.home.name} ${score.home}-${score.away} ${m.away.name}"
            }
        }
    }

    private fun contentText(activity: LiveActivity.MatchActivity): String {
        val m = activity.match
        return when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> {
                val lineups = activity.lineups
                when {
                    lineups?.isConfirmed == true -> {
                        val h = lineups.home?.formation.orEmpty()
                        val a = lineups.away?.formation.orEmpty()
                        if (h.isNotBlank() && a.isNotBlank()) "Line-ups in · $h vs $a"
                        else "Line-ups confirmed"
                    }
                    else -> countdownText(m.kickoffAt)
                }
            }
            LiveActivity.MatchActivity.Stage.LIVE ->
                activity.latestEvent?.headline() ?: "${m.clockLabel} · in play"
            LiveActivity.MatchActivity.Stage.FULL_TIME -> {
                val ht = m.halfTimeScore?.let { " (HT $it)" }.orEmpty()
                "Full time$ht"
            }
        }
    }

    private fun countdownText(kickoff: Instant): String {
        val minutes = Duration.between(Instant.now(), kickoff).toMinutes()
        return when {
            minutes > 90 -> "Kick-off ${TIME_FORMAT.format(kickoff.atZone(ZoneId.systemDefault()))}"
            minutes > 1 -> context.getString(R.string.live_kickoff_in, "$minutes min")
            minutes >= 0 -> context.getString(R.string.live_starting_soon)
            else -> context.getString(R.string.live_starting_soon)
        }
    }

    /**
     * The status-bar chip text. At most seven characters, and it must use the same
     * format as the expanded card so the two never disagree.
     */
    private fun shortCriticalText(activity: LiveActivity.MatchActivity): String {
        val m = activity.match
        return when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> {
                val minutes = Duration.between(Instant.now(), m.kickoffAt).toMinutes()
                if (minutes in 0..99) "${minutes}m" else "Soon"
            }
            LiveActivity.MatchActivity.Stage.LIVE ->
                m.score?.let { "${it.home}-${it.away}" } ?: m.clockLabel.take(7)
            LiveActivity.MatchActivity.Stage.FULL_TIME ->
                m.score?.let { "FT ${it.home}-${it.away}".take(7) } ?: "FT"
        }
    }

    // ---- promoted (Live Update) ---------------------------------------------

    /**
     * The bar *is* the match clock: two 45-minute segments for the halves, a point for
     * every goal at the minute it went in, coloured by the side that scored, and the
     * ball tracker sitting on the current minute. Start and end icons are the crests.
     */
    private fun applyPromoted(
        builder: NotificationCompat.Builder,
        activity: LiveActivity.MatchActivity,
        crests: Crests?,
    ) {
        val match = activity.match
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(match.progressMinutes)
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(Match.HALF_MINUTES)
                        .setId(SEGMENT_FIRST_HALF)
                        .setColor(color(R.color.brand_green)),
                    NotificationCompat.ProgressStyle.Segment(Match.HALF_MINUTES)
                        .setId(SEGMENT_SECOND_HALF)
                        .setColor(color(R.color.notif_accent)),
                ),
            )
            .setProgressPoints(goalPoints(activity))
            .setProgressTrackerIcon(
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    context, R.drawable.ic_ball_tracker,
                ),
            )

        crests?.let {
            style.setProgressStartIcon(
                androidx.core.graphics.drawable.IconCompat.createWithBitmap(it.home),
            )
            style.setProgressEndIcon(
                androidx.core.graphics.drawable.IconCompat.createWithBitmap(it.away),
            )
        }

        builder.setStyle(style)
            .setShortCriticalText(shortCriticalText(activity))
            .setRequestPromotedOngoing(true)

        if (activity.stage == LiveActivity.MatchActivity.Stage.PRE_MATCH) {
            // A countdown in the header, matching the chip's "43m".
            builder.setWhen(match.kickoffAt.toEpochMilli())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
    }

    /** One [NotificationCompat.ProgressStyle.Point] per goal, at the minute it was scored. */
    private fun goalPoints(
        activity: LiveActivity.MatchActivity,
    ): List<NotificationCompat.ProgressStyle.Point> =
        activity.recentEvents
            .asSequence()
            .filter { it.type.isGoal || it.type == com.tzvi.kickoff.core.model.MatchEventType.RED_CARD }
            .mapNotNull { event ->
                // Point positions are 1-based and relative to the summed segment length.
                val position = event.minute?.coerceIn(1, Match.REGULATION_MINUTES) ?: return@mapNotNull null
                NotificationCompat.ProgressStyle.Point(position)
                    .setId(event.id.hashCode())
                    .setColor(pointColor(event))
            }
            .distinctBy { it.position }
            .toList()

    private fun pointColor(event: MatchEvent): Int = when {
        event.type == com.tzvi.kickoff.core.model.MatchEventType.RED_CARD -> color(R.color.notif_live)
        event.side == MatchSide.AWAY -> AWAY_GOAL_COLOR
        else -> HOME_GOAL_COLOR
    }

    // ---- rich (custom scoreboard) -------------------------------------------

    private fun applyRich(
        builder: NotificationCompat.Builder,
        activity: LiveActivity.MatchActivity,
        crests: Crests,
    ) {
        builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedViews(activity, crests))
            .setCustomBigContentView(expandedViews(activity, crests))
            .setCustomHeadsUpContentView(collapsedViews(activity, crests))
    }

    private fun collapsedViews(
        activity: LiveActivity.MatchActivity,
        crests: Crests,
    ): RemoteViews {
        val m = activity.match
        return RemoteViews(context.packageName, R.layout.notification_match_collapsed).apply {
            setImageViewBitmap(R.id.crest_home, crests.home)
            setImageViewBitmap(R.id.crest_away, crests.away)
            setTextViewText(R.id.code_home, m.home.code)
            setTextViewText(R.id.code_away, m.away.code)
            setTextViewText(R.id.score, m.score?.let { "${it.home} - ${it.away}" } ?: "vs")
            setTextViewText(R.id.clock, collapsedClock(activity))
        }
    }

    private fun collapsedClock(activity: LiveActivity.MatchActivity): String = when (activity.stage) {
        LiveActivity.MatchActivity.Stage.PRE_MATCH ->
            TIME_FORMAT.format(activity.match.kickoffAt.atZone(ZoneId.systemDefault()))
        LiveActivity.MatchActivity.Stage.LIVE -> activity.match.clockLabel
        LiveActivity.MatchActivity.Stage.FULL_TIME -> "FT"
    }

    private fun expandedViews(
        activity: LiveActivity.MatchActivity,
        crests: Crests,
    ): RemoteViews {
        val m = activity.match
        return RemoteViews(context.packageName, R.layout.notification_match_expanded).apply {
            crests.league?.let { setImageViewBitmap(R.id.league_logo, it) }
                ?: setViewVisibility(R.id.league_logo, View.GONE)
            setTextViewText(
                R.id.league_name,
                listOfNotNull(m.leagueName.takeIf { it.isNotBlank() }, m.round)
                    .joinToString(" · "),
            )

            setImageViewBitmap(R.id.crest_home, crests.home)
            setImageViewBitmap(R.id.crest_away, crests.away)
            setTextViewText(R.id.name_home, m.home.name)
            setTextViewText(R.id.name_away, m.away.name)
            setTextViewText(R.id.score_home, m.score?.home?.toString() ?: "–")
            setTextViewText(R.id.score_away, m.score?.away?.toString() ?: "–")

            setTextViewText(R.id.clock, collapsedClock(activity))
            setProgressBar(
                R.id.match_progress,
                Match.REGULATION_MINUTES,
                m.progressMinutes,
                false,
            )

            if (activity.stage == LiveActivity.MatchActivity.Stage.LIVE) {
                setViewVisibility(R.id.live_pill, View.VISIBLE)
                setTextViewText(R.id.live_pill, "LIVE")
            } else {
                setViewVisibility(R.id.live_pill, View.GONE)
            }

            applyEventStrip(this, activity)
            applyLineups(this, activity)
            applyStats(this, activity)
        }
    }

    private fun applyEventStrip(views: RemoteViews, activity: LiveActivity.MatchActivity) {
        val event = activity.latestEvent
        if (event != null && activity.stage != LiveActivity.MatchActivity.Stage.PRE_MATCH) {
            views.setViewVisibility(R.id.event_strip, View.VISIBLE)
            views.setTextViewText(R.id.event_minute, event.minuteLabel)
            views.setTextViewText(R.id.event_text, event.headline())
        } else if (activity.stage == LiveActivity.MatchActivity.Stage.PRE_MATCH) {
            views.setViewVisibility(R.id.event_strip, View.VISIBLE)
            views.setTextViewText(R.id.event_minute, "")
            views.setTextViewText(R.id.event_text, contentText(activity))
        } else {
            views.setViewVisibility(R.id.event_strip, View.GONE)
        }
    }

    private fun applyLineups(views: RemoteViews, activity: LiveActivity.MatchActivity) {
        val lineups = activity.lineups
        val home = lineups?.home
        val away = lineups?.away
        if (activity.stage != LiveActivity.MatchActivity.Stage.PRE_MATCH ||
            home == null || away == null
        ) {
            views.setViewVisibility(R.id.lineup_block, View.GONE)
            return
        }
        views.setViewVisibility(R.id.lineup_block, View.VISIBLE)
        views.setTextViewText(R.id.lineup_home_formation, home.formation.orEmpty())
        views.setTextViewText(R.id.lineup_away_formation, away.formation.orEmpty())
        views.setTextViewText(R.id.lineup_home_players, compactXi(home))
        views.setTextViewText(R.id.lineup_away_players, compactXi(away))
    }

    /** Surnames only - a full XI has to fit four short lines. */
    private fun compactXi(lineup: TeamLineup): String =
        lineup.startingXi.joinToString(" · ") { it.surname }

    private fun applyStats(views: RemoteViews, activity: LiveActivity.MatchActivity) {
        val possession = activity.statistics?.pair(MatchStatistics.POSSESSION)
        if (activity.stage != LiveActivity.MatchActivity.Stage.PRE_MATCH && possession != null) {
            views.setViewVisibility(R.id.stats_block, View.VISIBLE)
            views.setTextViewText(R.id.stat_home, possession.first)
            views.setTextViewText(R.id.stat_label, "possession")
            views.setTextViewText(R.id.stat_away, possession.second)
        } else {
            views.setViewVisibility(R.id.stats_block, View.GONE)
        }
    }

    // ---- plain ---------------------------------------------------------------

    private fun applyPlain(
        builder: NotificationCompat.Builder,
        activity: LiveActivity.MatchActivity,
    ) {
        val body = buildString {
            append(contentText(activity))
            activity.recentEvents.takeLast(4).forEach { event ->
                append('\n')
                append(event.minuteLabel).append("  ").append(event.headline())
            }
        }
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        if (Build.VERSION.SDK_INT < 36) {
            // ProgressStyle renders nothing below API 36 - it does not fall back to a
            // legacy bar - so the old-style determinate bar is set explicitly here.
            builder.setProgress(
                Match.REGULATION_MINUTES,
                activity.match.progressMinutes,
                false,
            )
        }
    }

    // ---- intents -------------------------------------------------------------

    private fun openMatchIntent(matchId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("kickoff://match/$matchId".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            matchId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Swipe-away handler. The Live Update guidance is explicit that a dismissed card
     * must not be reposted, so the dismissal is recorded rather than ignored.
     */
    private fun dismissIntent(key: String): PendingIntent =
        NotificationActionReceiver.pendingIntent(
            context, NotificationActionReceiver.ACTION_DISMISSED, key, null,
        )

    private fun stopFollowingIntent(key: String, matchId: Long): PendingIntent =
        NotificationActionReceiver.pendingIntent(
            context, NotificationActionReceiver.ACTION_STOP_FOLLOWING, key, matchId,
        )

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    private fun String.toUri() = android.net.Uri.parse(this)

    private companion object {
        const val SEGMENT_FIRST_HALF = 1
        const val SEGMENT_SECOND_HALF = 2
        const val HOME_GOAL_COLOR = 0xFF00C853.toInt()
        const val AWAY_GOAL_COLOR = 0xFF4FC3F7.toInt()
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
