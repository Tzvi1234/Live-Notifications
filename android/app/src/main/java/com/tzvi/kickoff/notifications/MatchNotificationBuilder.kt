package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.tzvi.kickoff.MainActivity
import com.tzvi.kickoff.R
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.LiveCardStyle
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
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
 * Everything here stays inside the rules for a *promoted* ongoing notification, because
 * promotion is the only thing that puts a card on the status-bar chip, keeps it expanded
 * on the lock screen and gets it onto an always-on display. Those rules are narrow and
 * verified against AOSP rather than assumed:
 *
 *  - Custom `RemoteViews` disqualify a notification outright - `setCustomContentView`,
 *    `setCustomBigContentView`, `setCustomHeadsUpContentView`, and the same three on any
 *    `publicVersion`. There is no escape hatch, so the prettier hand-drawn scoreboard
 *    this class used to build has been removed rather than kept as a second-class option:
 *    it could never appear anywhere but the shade.
 *  - `setColorized(true)` also disqualifies it, from Android 16 QPR1 onwards. (On 16.0
 *    the rule was the exact opposite and colorization was *required* - which is why this
 *    only ever promotes on QPR1+, where one consistent set of rules applies.)
 *  - Only five styles may be promoted: none, `BigTextStyle`, `CallStyle`, `MetricStyle`
 *    and `ProgressStyle`. The two used here are the last and the second.
 *
 * What reaches the always-on display is narrower still: SystemUI does not draw your
 * notification there at all, it re-inflates the platform template into a monochrome
 * white-on-black skeleton with every span stripped, every colour discarded, actions
 * dropped and a colour large icon dropped entirely. So the score lives in the *title*,
 * which is the largest thing on that surface, and `BigTextStyle` is the only style whose
 * long text survives to it. The two renderings below follow from exactly that.
 */
@Singleton
class MatchNotificationBuilder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capability: LiveUpdateCapability,
) {
    /** Which of the three faces a given post used, so callers can report it honestly. */
    enum class Rendering {
        /** ProgressStyle: the match clock as a bar. Promoted where the device allows it. */
        CLOCK,

        /** BigTextStyle: a running commentary. The only text that reaches the AOD. */
        COMMENTARY,

        /** System template, nothing requested. The fallback that always works. */
        PLAIN,
    }

    data class Result(val notification: Notification, val rendering: Rendering)

    data class Crests(val home: Bitmap, val away: Bitmap, val league: Bitmap?)

    fun build(
        activity: LiveActivity.MatchActivity,
        crests: Crests?,
        style: LiveCardStyle,
    ): Result {
        val rendering = chooseRendering(style, crests)
        val builder = baseBuilder(activity)

        when (rendering) {
            Rendering.CLOCK -> applyClock(builder, activity, crests)
            Rendering.COMMENTARY -> applyCommentary(builder, activity, crests)
            Rendering.PLAIN -> applyPlain(builder, activity)
        }
        return Result(builder.build(), rendering)
    }

    /**
     * The interrupting half of a goal or a red card.
     *
     * This is a separate notification rather than a louder repost of the live card,
     * because a notification's channel is fixed per post: pushing the ongoing card onto
     * the high-importance channel for one goal would move it between channels on every
     * event, and re-alert the scoreboard every time it moved back.
     */
    fun buildEventAlert(
        activity: LiveActivity.MatchActivity,
        event: MatchEvent,
        crests: Crests?,
    ): Notification {
        val match = activity.match
        val score = match.score?.let { " ${it.home}-${it.away}" }.orEmpty()
        return NotificationCompat.Builder(context, NotificationChannels.MATCH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_kickoff)
            .setContentTitle("${match.home.code}$score ${match.away.code}")
            .setContentText("${event.minuteLabel}  ${event.headline()}")
            .setSubText(match.leagueName.takeIf { it.isNotBlank() })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${event.minuteLabel}  ${event.headline()}")
                    .setSummaryText("${match.home.name} v ${match.away.name}"),
            )
            .setLargeIcon(if (event.side == MatchSide.AWAY) crests?.away else crests?.home)
            .setContentIntent(openMatchIntent(match.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(color(R.color.brand_green))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /** Alerts get their own ids so several can stack, and dismissing one keeps the card. */
    fun alertNotificationId(event: MatchEvent): Int = event.id.hashCode() and 0x7FFFFFFF

    private fun chooseRendering(style: LiveCardStyle, crests: Crests?): Rendering = when (style) {
        LiveCardStyle.PLAIN -> Rendering.PLAIN
        // BigTextStyle needs nothing from the platform and renders on every version; the
        // crests only decorate it, so this one never has to fall back.
        LiveCardStyle.RICH -> Rendering.COMMENTARY
        LiveCardStyle.AUTO -> when {
            // Promotion is what buys the chip, the lock screen and the AOD, so it wins
            // whenever the device and the user allow it.
            capability.supportsProgressStyle && capability.canPostPromoted() -> Rendering.CLOCK
            else -> Rendering.COMMENTARY
        }
    }

    // ---- shared scaffolding --------------------------------------------------

    private fun baseBuilder(activity: LiveActivity.MatchActivity): NotificationCompat.Builder {
        val match = activity.match
        return NotificationCompat.Builder(context, NotificationChannels.LIVE_MATCH)
            .setSmallIcon(R.drawable.ic_stat_kickoff)
            .setContentTitle(contentTitle(activity))
            .setContentText(contentText(activity))
            .setSubText(match.leagueName.takeIf { it.isNotBlank() })
            .setContentIntent(openMatchIntent(match.id))
            .setDeleteIntent(dismissIntent(activity.key))
            // The scoreboard updates every few seconds for ninety minutes: it must never
            // make a sound. Goals interrupt through buildEventAlert instead.
            .setOngoing(activity.stage != LiveActivity.MatchActivity.Stage.FULL_TIME)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
    private fun applyClock(
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

    // ---- commentary (BigTextStyle) ------------------------------------------

    /**
     * A running teleprinter of the match, and the closest a promoted notification gets to
     * an iOS Live Activity.
     *
     * `BigTextStyle` earns its place for one reason: it is the only promotable style whose
     * long text is carried to the always-on display. Every other style hands the AOD a
     * single `contentText` line. So the last few events go in `bigText`, the score goes in
     * the title where the AOD draws it largest, and the crests ride along as a large icon
     * for the surfaces that still have colour.
     */
    private fun applyCommentary(
        builder: NotificationCompat.Builder,
        activity: LiveActivity.MatchActivity,
        crests: Crests?,
    ) {
        val match = activity.match
        builder.setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(commentaryText(activity))
                .setBigContentTitle(contentTitle(activity))
                .setSummaryText(headline(activity)),
        )
            .setSubText(subText(activity))
            .setShortCriticalText(shortCriticalText(activity))

        // Full colour here on purpose. The shade and the lock screen show it; the AOD
        // drops any non-grayscale large icon, and on that surface the title and the
        // commentary are doing the work anyway.
        crests?.let { builder.setLargeIcon(crestPair(it)) }

        if (capability.canPostPromoted()) builder.setRequestPromotedOngoing(true)

        if (activity.stage == LiveActivity.MatchActivity.Stage.PRE_MATCH) {
            builder.setWhen(match.kickoffAt.toEpochMilli())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
    }

    /** "Premier League - 78'", the header line above the title. */
    private fun subText(activity: LiveActivity.MatchActivity): String {
        val league = activity.match.leagueName.takeIf { it.isNotBlank() }
        val clock = when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> null
            LiveActivity.MatchActivity.Stage.LIVE -> activity.match.clockLabel
            LiveActivity.MatchActivity.Stage.FULL_TIME -> "Full time"
        }
        return listOfNotNull(league, clock).joinToString(" \u00b7 ")
    }

    private fun headline(activity: LiveActivity.MatchActivity): String =
        activity.latestEvent?.headline() ?: contentText(activity)

    /**
     * Newest first, because the AOD card is height-capped and the top of the block is the
     * part that always survives. Before kick-off there is no commentary to give, so the
     * formations stand in for it.
     */
    private fun commentaryText(activity: LiveActivity.MatchActivity): CharSequence {
        if (activity.stage == LiveActivity.MatchActivity.Stage.PRE_MATCH) {
            val lineups = activity.lineups
            val home = lineups?.home
            val away = lineups?.away
            if (home != null && away != null) {
                return buildString {
                    append(countdownText(activity.match.kickoffAt))
                    home.formation?.let { append("\n\n${home.teamName}  $it") }
                    away.formation?.let { append("\n${away.teamName}  $it") }
                }
            }
            return countdownText(activity.match.kickoffAt)
        }

        val lines = activity.recentEvents
            .asSequence()
            .sortedByDescending { it.minute ?: 0 }
            .take(COMMENTARY_LINES)
            .map { event ->
                val minute = event.minuteLabel.padEnd(4)
                "$minute ${eventGlyph(event)} ${event.headline()}"
            }
            .toList()

        if (lines.isEmpty()) return contentText(activity)
        return lines.joinToString("\n")
    }

    /**
     * A single character, not an emoji font: the AOD strips styling but keeps codepoints,
     * and these read at any size where a coloured icon would have been discarded.
     */
    private fun eventGlyph(event: MatchEvent): String = when {
        event.type.isGoal -> "\u26bd"
        event.type == MatchEventType.RED_CARD -> "\ud83d\udfe5"
        event.type == MatchEventType.YELLOW_CARD -> "\ud83d\udfe8"
        event.type == MatchEventType.SUBSTITUTION -> "\u21c4"
        else -> "\u2022"
    }

    /**
     * Both crests on one 16:9 bitmap.
     *
     * 16:9 because that is the widest aspect the system will not crop: a large icon is
     * capped at 48dp tall, and the AOD widens it to at most 48dp x 16/9 before clamping.
     * Anything bigger is silently downscaled, so it is generated at exactly that size.
     */
    private fun crestPair(crests: Crests): Bitmap {
        val density = context.resources.displayMetrics.density
        val height = (LARGE_ICON_DP * density).toInt().coerceAtLeast(1)
        val width = (height * 16f / 9f).toInt().coerceAtLeast(height)
        val crestSize = (height * 0.82f).toInt().coerceAtLeast(1)
        val top = (height - crestSize) / 2f

        val output = createBitmap(width, height)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        canvas.drawBitmap(
            crests.home.scale(crestSize, crestSize),
            0f,
            top,
            paint,
        )
        canvas.drawBitmap(
            crests.away.scale(crestSize, crestSize),
            (width - crestSize).toFloat(),
            top,
            paint,
        )
        return output
    }

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
            .setData(Uri.parse("kickoff://match/$matchId"))
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

    private companion object {
        const val COMMENTARY_LINES = 5
        const val LARGE_ICON_DP = 48
        const val SEGMENT_FIRST_HALF = 1
        const val SEGMENT_SECOND_HALF = 2
        const val HOME_GOAL_COLOR = 0xFF00C853.toInt()
        const val AWAY_GOAL_COLOR = 0xFF4FC3F7.toInt()
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
