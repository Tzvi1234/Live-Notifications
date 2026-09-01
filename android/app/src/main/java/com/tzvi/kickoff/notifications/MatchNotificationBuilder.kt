package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
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

        /** The hand-drawn score card. Custom views, so never promoted - by choice. */
        SCOREBOARD,
    }

    data class Result(val notification: Notification, val rendering: Rendering)

    data class Crests(val home: Bitmap, val away: Bitmap, val league: Bitmap?)

    /** Composed crest pairs, keyed by the identity of the two crests. See [crestPair]. */
    private val pairCache = LruCache<Long, Bitmap>(PAIR_CACHE_ENTRIES)

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
            Rendering.SCOREBOARD -> applyScoreboard(builder, activity, requireNotNull(crests))
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
            .setColor(alertAccent(event))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /**
     * The header accent for an interrupting event: red when someone has been sent off.
     *
     * A theme resource rather than one of the bar's fixed mark colours, because this
     * colour lands on the notification surface and has to hold up in both themes. The
     * marks are pinned constants for the opposite reason - they sit on the progress bar,
     * where a surface palette would disappear into the track.
     */
    private fun alertAccent(event: MatchEvent): Int = when (event.type) {
        MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW -> color(R.color.notif_live)
        else -> color(R.color.brand_green)
    }

    /** Alerts get their own ids so several can stack, and dismissing one keeps the card. */
    fun alertNotificationId(event: MatchEvent): Int = event.id.hashCode() and 0x7FFFFFFF

    private fun chooseRendering(style: LiveCardStyle, crests: Crests?): Rendering = when (style) {
        LiveCardStyle.PLAIN -> Rendering.PLAIN
        // The card IS the crests and the score; without bitmaps there is nothing to draw.
        LiveCardStyle.SCOREBOARD -> if (crests != null) Rendering.SCOREBOARD else Rendering.PLAIN
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

    /**
     * The card's one line of prose.
     *
     * A goal is worth shouting about for a few minutes and then it is just clutter: the
     * scoreline already carries the fact that it happened, and a card that still says
     * "Havertz - assist Rice" in the 88th minute is stale furniture. So an event holds
     * the line for [EVENT_LINE_MINUTES] match minutes and the card then goes back to
     * being clean.
     */
    private fun contentText(activity: LiveActivity.MatchActivity): String {
        val m = activity.match
        return when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> preMatchText(activity)
            LiveActivity.MatchActivity.Stage.LIVE ->
                freshEvent(activity)?.headline() ?: "${m.clockLabel} \u00b7 in play"
            LiveActivity.MatchActivity.Stage.FULL_TIME -> {
                val ht = m.halfTimeScore?.let { " (HT $it)" }.orEmpty()
                "Full time$ht"
            }
        }
    }

    /** The latest event, but only while it is still news. */
    private fun freshEvent(activity: LiveActivity.MatchActivity): MatchEvent? =
        activity.latestEvent?.takeIf { isFresh(it, activity.match.elapsedMinutes) }

    /**
     * The one expiry rule, shared by the prose line and the bar's tracker symbol so the
     * two never disagree about whether something is still news. A missing minute on
     * either side means we cannot age the event, and an event we cannot age is kept.
     */
    private fun isFresh(event: MatchEvent, elapsedMinutes: Int?): Boolean {
        val now = elapsedMinutes ?: return true
        val minute = event.minute ?: return true
        return now - minute <= EVENT_LINE_MINUTES
    }

    /**
     * Before kick-off the card is the team sheet.
     *
     * This is the half of the feature that only exists in the hour before a match, and it
     * had nowhere to appear: the countdown alone repeats what the header already says.
     */
    private fun preMatchText(activity: LiveActivity.MatchActivity): String {
        val lineups = activity.lineups
        val home = lineups?.home
        val away = lineups?.away
        val countdown = countdownText(activity.match.kickoffAt)
        if (home == null || away == null) return countdown

        val formations = listOfNotNull(home.formation, away.formation)
        return if (formations.size == 2) {
            "$countdown \u00b7 ${formations[0]} vs ${formations[1]}"
        } else {
            "$countdown \u00b7 Line-ups in"
        }
    }

    /** The full team sheet, for the styles with room to print it. */
    private fun lineupBlock(activity: LiveActivity.MatchActivity): String? {
        val lineups = activity.lineups ?: return null
        val home = lineups.home ?: return null
        val away = lineups.away ?: return null
        if (home.startingXi.isEmpty() || away.startingXi.isEmpty()) return null
        return buildString {
            append(home.teamName)
            home.formation?.let { append("  $it") }
            append("\n")
            append(home.startingXi.joinToString(", ") { it.surname })
            append("\n\n")
            append(away.teamName)
            away.formation?.let { append("  $it") }
            append("\n")
            append(away.startingXi.joinToString(", ") { it.surname })
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
     * The status-bar chip text. At most [CHIP_CHARS] characters, and it must use the
     * same format as the expanded card so the two never disagree.
     *
     * This one string is the whole of what the chip can be told. The chip renders the
     * notification's small icon force-tinted to a single system colour, plus exactly one
     * of this text, a chronometer or a short time delta - there is no bitmap slot, so
     * the crests stop at the shade and cannot follow the card into the status bar. The
     * score is therefore what has to survive; the minute is appended only when both
     * still fit, because a truncated chip reads worse than a short one.
     */
    private fun shortCriticalText(activity: LiveActivity.MatchActivity): String {
        val m = activity.match
        return when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> {
                val minutes = Duration.between(Instant.now(), m.kickoffAt).toMinutes()
                if (minutes in 0..99) "${minutes}m" else "Soon"
            }
            LiveActivity.MatchActivity.Stage.LIVE -> {
                val score = m.score?.let { "${it.home}-${it.away}" }
                val clock = m.clockLabel.takeIf { it.isNotBlank() }
                when {
                    score == null -> clock?.take(CHIP_CHARS).orEmpty()
                    clock == null -> score
                    // "2-1 67'" is exactly the budget; "2-1 45+2'" is not, and a
                    // scoreline on its own is the half worth keeping.
                    else -> "$score $clock".takeIf { it.length <= CHIP_CHARS } ?: score
                }
            }
            LiveActivity.MatchActivity.Stage.FULL_TIME ->
                m.score?.let { "FT ${it.home}-${it.away}".take(CHIP_CHARS) } ?: "FT"
        }
    }

    // ---- promoted (Live Update) ---------------------------------------------

    /**
     * The bar *is* the match clock: two 45-minute segments for the halves, a coloured
     * mark at the minute of each of the four most notable incidents, and the tracker
     * riding the current minute carrying the symbol of whatever has just happened. The
     * crests bookend the bar at the 20dp those two slots are pinned to, and ride the
     * header as a composed pair, which is both larger and the only one of the two that
     * survives into the collapsed card.
     *
     * All of that is the *expanded* card and nowhere else: the collapsed and heads-up
     * views call hideProgress(true) and never bind the bar at all, and the always-on
     * display repaints every segment and point white and merges the segments into one -
     * so on that surface the bar is decoration, and the title carries the score.
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
            .setProgressPoints(eventPoints(activity))
            .setProgressTrackerIcon(trackerIcon(activity))

        crests?.let {
            // The two ends of the bar. Fixed at 20dp by the platform, hence the large
            // icon below - this is not where a crest becomes legible.
            style.setProgressStartIcon(IconCompat.createWithBitmap(it.home))
            style.setProgressEndIcon(IconCompat.createWithBitmap(it.away))
            builder.setLargeIcon(crestPair(it))
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

    /**
     * Up to four incidents, each a dot on the bar at the minute it happened, coloured by
     * the side it belongs to.
     *
     * A `Point` carries an id and a colour and nothing else - neither the platform nor
     * androidx has an icon field on `Point` or `Segment` - so the bar can say *when* an
     * incident happened and *whose* it was, and the tracker icon has to say *what* it
     * was. Four is the platform's own cap on points (ten on segments), so when a match
     * runs past four incidents the significant ones win and, among equals, the recent
     * ones. [markWeight] is the same ranking the alerting path uses, so the dot that
     * survives is the one the user was interrupted for.
     */
    private fun eventPoints(
        activity: LiveActivity.MatchActivity,
    ): List<NotificationCompat.ProgressStyle.Point> =
        activity.recentEvents
            .mapNotNull { event ->
                if (markIcon(event.type) == null) return@mapNotNull null
                // Point positions are 1-based and relative to the summed segment length.
                val minute = event.minute ?: return@mapNotNull null
                event to minute.coerceIn(1, Match.REGULATION_MINUTES)
            }
            .sortedWith(
                compareByDescending<Pair<MatchEvent, Int>> { markWeight(it.first.type) }
                    .thenByDescending { it.second },
            )
            // Two incidents in the same minute would land on the same dot; the better
            // ranked of the two keeps it.
            .distinctBy { it.second }
            .take(MAX_POINTS)
            .sortedBy { it.second }
            .map { (event, position) ->
                NotificationCompat.ProgressStyle.Point(position)
                    .setId(event.id.hashCode())
                    .setColor(sideColor(event.side))
            }

    /**
     * The tracker sits on the current minute, which makes it the one thing on the bar
     * that can say what just happened as well as where we are: a ball in the scoring
     * side's colour, a yellow or a red card, or the penalty spot.
     *
     * It is also the *only* place a symbol can go. `ProgressStyle` has exactly three
     * icon slots - the two ends of the bar and this one - and none of them can be pinned
     * to a point, so a per-incident icon along the bar is not something to be worked
     * around; it does not exist.
     *
     * The symbol expires on the same [EVENT_LINE_MINUTES] rule the card's prose line
     * uses, after which the tracker goes back to the plain ball and the card reads clean.
     */
    private fun trackerIcon(activity: LiveActivity.MatchActivity): IconCompat {
        val event = freshMark(activity)
        val tracker = IconCompat.createWithResource(
            context,
            event?.type?.let(::markIcon) ?: R.drawable.ic_ball_tracker,
        )
        // Only an incident's symbol is tinted; the idle ball keeps the drawable's white.
        return if (event == null) tracker else tracker.setTint(markTint(event))
    }

    /** The newest incident the bar has a symbol for, while it is still news. */
    private fun freshMark(activity: LiveActivity.MatchActivity): MatchEvent? =
        activity.recentEvents.lastOrNull {
            markIcon(it.type) != null && isFresh(it, activity.match.elapsedMinutes)
        }

    /** The symbol for an incident, or null for the kinds the bar does not mark. */
    @DrawableRes
    private fun markIcon(type: MatchEventType): Int? = when (type) {
        MatchEventType.GOAL, MatchEventType.OWN_GOAL -> R.drawable.ic_event_goal
        MatchEventType.PENALTY_GOAL, MatchEventType.PENALTY_MISSED -> R.drawable.ic_event_penalty
        MatchEventType.YELLOW_CARD, MatchEventType.SECOND_YELLOW, MatchEventType.RED_CARD ->
            R.drawable.ic_event_card
        else -> null
    }

    /**
     * What the symbol is tinted.
     *
     * A card's colour *is* the card - it is the whole of what a booking says - so that
     * wins. Everything else takes the colour of the side it belongs to, which is what
     * makes the ball answer "whose goal?" on its own.
     */
    private fun markTint(event: MatchEvent): Int = when (event.type) {
        MatchEventType.YELLOW_CARD -> CARD_YELLOW_COLOR
        MatchEventType.SECOND_YELLOW, MatchEventType.RED_CARD -> CARD_RED_COLOR
        else -> sideColor(event.side)
    }

    /** How much an incident deserves one of the four points. Mirrors the alert ranking. */
    private fun markWeight(type: MatchEventType): Int = when {
        type.isGoal -> 100
        type == MatchEventType.RED_CARD || type == MatchEventType.SECOND_YELLOW -> 80
        type == MatchEventType.PENALTY_MISSED -> 70
        type == MatchEventType.YELLOW_CARD -> 40
        else -> 0
    }

    /**
     * A side's colour on the bar.
     *
     * Deliberately a fixed pair rather than anything sampled from the crest: a crest's
     * dominant colour is as often green as not, which is the track's own colour, and two
     * clubs in the same shirt - Arsenal against Manchester United - would come out
     * indistinguishable on a dot a few pixels wide. Neutral covers the whistles and VAR,
     * which belong to neither side.
     */
    private fun sideColor(side: MatchSide): Int = when (side) {
        MatchSide.HOME -> HOME_MARK_COLOR
        MatchSide.AWAY -> AWAY_MARK_COLOR
        MatchSide.NEUTRAL -> NEUTRAL_MARK_COLOR
    }

    // ---- scoreboard (custom views) ------------------------------------------

    /**
     * The reference widget, remade: a near-black card with a crest and a huge score on
     * each side, the clock small between them, and the names along the bottom.
     *
     * Deliberately NOT DecoratedCustomViewStyle - the system header and action row are
     * exactly the chrome the reference does not have. Losing the Stop following button
     * here is part of the style's stated trade; the delete intent and the tap-through
     * still work, and the other styles keep the button.
     */
    private fun applyScoreboard(
        builder: NotificationCompat.Builder,
        activity: LiveActivity.MatchActivity,
        crests: Crests,
    ) {
        val match = activity.match
        val score = match.score
        val clock = when (activity.stage) {
            LiveActivity.MatchActivity.Stage.PRE_MATCH -> countdownText(match.kickoffAt)
            LiveActivity.MatchActivity.Stage.LIVE -> match.clockLabel
            LiveActivity.MatchActivity.Stage.FULL_TIME -> "FT"
        }

        val compact = RemoteViews(context.packageName, R.layout.notification_scoreboard).apply {
            setImageViewBitmap(R.id.home_crest, crests.home)
            setImageViewBitmap(R.id.away_crest, crests.away)
            setTextViewText(
                R.id.score,
                score?.let { "${it.home} \u2013 ${it.away}" } ?: "vs",
            )
            setTextViewText(R.id.clock, clock)
        }

        val big = RemoteViews(context.packageName, R.layout.notification_scoreboard_big).apply {
            setImageViewBitmap(R.id.home_crest, crests.home)
            setImageViewBitmap(R.id.away_crest, crests.away)
            setTextViewText(R.id.home_score, score?.home?.toString() ?: "\u2013")
            setTextViewText(R.id.away_score, score?.away?.toString() ?: "\u2013")
            setTextViewText(R.id.clock, clock)
            setTextViewText(R.id.home_name, match.home.code)
            setTextViewText(R.id.away_name, match.away.code)
            setTextViewText(R.id.league_line, match.leagueName)
            // The venue when nothing has happened yet, the last event once something has:
            // the reference card's bottom line is context, and a goal is better context
            // than a stadium name.
            setTextViewText(
                R.id.event_line,
                freshEvent(activity)?.headline()
                    ?: preMatchFormations(activity)
                    ?: match.venue.orEmpty(),
            )
        }

        builder.setCustomContentView(compact)
            .setCustomBigContentView(big)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
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

    /** "4-3-3 vs 4-2-3-1" while the teams are still in the tunnel. */
    private fun preMatchFormations(activity: LiveActivity.MatchActivity): String? {
        if (activity.stage != LiveActivity.MatchActivity.Stage.PRE_MATCH) return null
        val home = activity.lineups?.home?.formation ?: return null
        val away = activity.lineups?.away?.formation ?: return null
        return "$home vs $away"
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
            val sheet = lineupBlock(activity)
            return if (sheet != null) {
                "${countdownText(activity.match.kickoffAt)}\n\n$sheet"
            } else {
                countdownText(activity.match.kickoffAt)
            }
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
     * Both crests on one bitmap, as large as the slot they land in will draw them.
     *
     * That slot is `right_icon`, and it is a **square**: every template that carries a
     * large icon - the promoted progress one included - includes
     * `notification_right_icon`, which is [LARGE_ICON_DP] wide *and* tall with
     * `scaleType="centerCrop"`. A composition wider than it is tall is therefore not
     * shown wider, it is centre-cropped back to the square, which for a pair drawn at
     * the two edges means the crop keeps the gap and throws away both crests. So the
     * bitmap is square, and each crest takes half its width.
     *
     * That is 24dp a crest against the 20dp the bar's end icons are pinned to, and
     * unlike the bar it also survives into the collapsed card, which never draws the
     * progress bar at all. It is the largest either crest can be on this card.
     *
     * Cached per crest pair: a match posts hundreds of updates and this would otherwise
     * allocate a fresh bitmap for every one of them. [CrestLoader] hands back the same
     * crest instances for the whole match, so identity is a sound key, and reusing one
     * output instance also lets the RemoteViews bitmap cache dedupe it across updates.
     */
    private fun crestPair(crests: Crests): Bitmap {
        val key = (System.identityHashCode(crests.home).toLong() shl 32) or
            (System.identityHashCode(crests.away).toLong() and 0xFFFF_FFFFL)
        pairCache.get(key)?.let { return it }

        // Composed at the device's own density, so the system never resamples it on the
        // way in and never has to crop it on the way out.
        val density = context.resources.displayMetrics.density
        val size = (LARGE_ICON_DP * density).toInt().coerceAtLeast(2)
        val crestSize = size / 2
        val top = (size - crestSize) / 2

        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        // Drawn straight into a destination rect rather than through a scaled copy, so a
        // pair costs one bitmap rather than three.
        canvas.drawBitmap(crests.home, null, Rect(0, top, crestSize, top + crestSize), paint)
        canvas.drawBitmap(crests.away, null, Rect(crestSize, top, size, top + crestSize), paint)

        pairCache.put(key, output)
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

        /** How many match minutes an event holds the card's one prose line. */
        const val EVENT_LINE_MINUTES = 5

        /** What the status-bar chip will show before it truncates. */
        const val CHIP_CHARS = 7

        /** `notification_right_icon_size`: the large icon's square slot. */
        const val LARGE_ICON_DP = 48
        const val PAIR_CACHE_ENTRIES = 4
        const val SEGMENT_FIRST_HALF = 1
        const val SEGMENT_SECOND_HALF = 2

        /** The platform's cap on `ProgressStyle` points. Exceeding it drops the extras. */
        const val MAX_POINTS = 4

        /**
         * The mark palette. Amber and cyan because they have to be told apart from each
         * other *and* from the track, which is green in both themes; the card colours
         * are the cards themselves.
         */
        const val HOME_MARK_COLOR = 0xFFFFAB00.toInt()
        const val AWAY_MARK_COLOR = 0xFF40C4FF.toInt()
        const val NEUTRAL_MARK_COLOR = 0xFFECEFF1.toInt()
        const val CARD_YELLOW_COLOR = 0xFFFFD600.toInt()
        const val CARD_RED_COLOR = 0xFFE53935.toInt()
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
