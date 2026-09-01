package com.tzvi.kickoff.notifications

import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.local.toEntity
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a data-only FCM payload into a live card.
 *
 * The push carries enough to render immediately without a network round trip, which is
 * the entire point: a goal has to be on screen in the second it happens, not after a
 * REST call. A fuller refresh is only kicked off when the payload is thin.
 */
@Singleton
class PushMessageHandler @Inject constructor(
    private val notifier: LiveActivityNotifier,
    private val settings: SettingsRepository,
    private val repository: FootballRepository,
    private val eventDao: MatchEventDao,
    private val trackedActivityDao: TrackedActivityDao,
) {
    suspend fun handle(data: Map<String, String>) {
        val config = settings.settings.first()
        if (!config.pushEnabled) return

        val matchId = data["matchId"]?.toLongOrNull() ?: return
        val key = LiveActivity.MatchActivity.matchKey(matchId)

        // Never resurrect a card the user swiped away.
        val tracked = trackedActivityDao.get(key)
        if (tracked?.dismissed == true) return

        // FCM guarantees neither ordering nor exactly-once delivery, so an older
        // sequence than the one already rendered is dropped rather than rendered.
        val sequence = data["seq"]?.toLongOrNull() ?: 0L
        if (tracked != null && sequence in 1..tracked.lastSequence) return

        val match = parseMatch(matchId, data) ?: return
        val event = parseEvent(matchId, data, match)

        // The same insert-ignore gate the poller uses: whichever path arrives first wins,
        // and the second one silently no-ops instead of alerting twice.
        val isNewEvent = event != null && eventDao.insertNew(listOf(event.toEntity())).isNotEmpty()

        val stored = repository.observeEvents(matchId).first()
        val activity = LiveActivity.MatchActivity(
            match = match,
            stage = stageOf(match),
            lineups = null,
            recentEvents = stored.ifEmpty { listOfNotNull(event) },
            statistics = null,
            sequence = sequence,
        )

        val alerting = event?.takeIf { isNewEvent && it.shouldAlert(config) }
        notifier.postMatch(activity, config.liveCardStyle, alerting)

        // A goal is worth a full refresh so the card picks up lineups, stats and any
        // events the push did not carry.
        if (isNewEvent && event.type.isGoal) {
            runCatching { repository.refreshMatch(matchId) }
        }
    }

    private fun parseMatch(matchId: Long, data: Map<String, String>): Match? {
        val homeId = data["homeId"]?.toIntOrNull() ?: return null
        val awayId = data["awayId"]?.toIntOrNull() ?: return null
        return Match(
            id = matchId,
            leagueId = data["leagueId"]?.toIntOrNull() ?: 0,
            leagueName = data["leagueName"].orEmpty(),
            leagueLogoUrl = data["leagueLogo"],
            round = data["round"],
            kickoffAt = data["kickoffAt"]?.toLongOrNull()
                ?.let(Instant::ofEpochSecond) ?: Instant.now(),
            venue = data["venue"],
            phase = runCatching { MatchPhase.valueOf(data["phase"].orEmpty()) }
                .getOrDefault(MatchPhase.UNKNOWN),
            elapsedMinutes = data["minute"]?.toIntOrNull(),
            extraMinutes = data["extra"]?.toIntOrNull(),
            home = Team(
                id = homeId,
                name = data["homeName"].orEmpty(),
                shortName = data["homeShort"] ?: data["homeName"].orEmpty().take(3).uppercase(),
                crestUrl = data["homeCrest"],
            ),
            away = Team(
                id = awayId,
                name = data["awayName"].orEmpty(),
                shortName = data["awayShort"] ?: data["awayName"].orEmpty().take(3).uppercase(),
                crestUrl = data["awayCrest"],
            ),
            score = data["homeScore"]?.toIntOrNull()?.let { home ->
                data["awayScore"]?.toIntOrNull()?.let { away -> Score(home, away) }
            },
        )
    }

    private fun parseEvent(matchId: Long, data: Map<String, String>, match: Match): MatchEvent? {
        val type = data["type"]?.let { raw ->
            runCatching { MatchEventType.valueOf(raw) }.getOrNull()
        } ?: return null
        val eventId = data["eventId"] ?: MatchEvent.key(
            matchId, type, data["minute"]?.toIntOrNull(), null, data["player"],
        )
        val side = runCatching { MatchSide.valueOf(data["side"].orEmpty()) }
            .getOrDefault(MatchSide.NEUTRAL)
        return MatchEvent(
            id = eventId,
            matchId = matchId,
            type = type,
            side = side,
            teamId = if (side == MatchSide.HOME) match.home.id else match.away.id,
            teamName = if (side == MatchSide.HOME) match.home.name else match.away.name,
            minute = data["minute"]?.toIntOrNull(),
            extraMinute = data["extra"]?.toIntOrNull(),
            playerName = data["player"],
            assistName = data["assist"],
            detail = data["detail"],
            comment = data["headline"],
            scoreAfter = match.score,
        )
    }

    private fun stageOf(match: Match) = when {
        match.phase.isFinished -> LiveActivity.MatchActivity.Stage.FULL_TIME
        match.isLive -> LiveActivity.MatchActivity.Stage.LIVE
        else -> LiveActivity.MatchActivity.Stage.PRE_MATCH
    }

    private fun MatchEvent.shouldAlert(
        config: com.tzvi.kickoff.core.model.AppSettings,
    ): Boolean = when {
        type.isGoal -> config.notifyGoals
        type.isCard -> config.notifyCards
        type == MatchEventType.SUBSTITUTION -> config.notifySubstitutions
        type == MatchEventType.KICK_OFF || type == MatchEventType.FULL_TIME ->
            config.notifyKickoffAndFullTime
        else -> false
    }
}
