package com.tzvi.kickoff.data.predict

import com.tzvi.kickoff.data.backend.MatchJson
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the prediction game, which lives entirely on the matchUP backend.
 *
 * None of this can be computed on the device and none of it can be trusted from one: a
 * guess is only meaningful if nobody can see anyone else's before kick-off and nobody can
 * change theirs after, and both of those are server rules enforced in SQL. The client
 * sends intent and renders what comes back.
 *
 * Timestamps are epoch seconds throughout, matching [MatchJson.kickoffAt].
 */
@Serializable
data class GroupJson(
    val id: Long,
    val name: String,
    val ownerId: String,
    val inviteCode: String,
    val leagueIds: List<Int> = emptyList(),
    val teamIds: List<Int> = emptyList(),
    val memberCount: Int = 0,
    val isOwner: Boolean = false,
    val createdAt: Long = 0,
    /** Present only on the detail route. */
    val members: List<GroupMemberJson> = emptyList(),
)

@Serializable
data class GroupListJson(val groups: List<GroupJson> = emptyList())

@Serializable
data class GroupMemberJson(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val joinedAt: Long = 0,
    val isOwner: Boolean = false,
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val leagueIds: List<Int> = emptyList(),
    val teamIds: List<Int> = emptyList(),
)

/**
 * An edit, which is the whole selection every time.
 *
 * The server treats each list as a replacement rather than a merge, so a caller that sent
 * only what changed would delete everything it left out.
 */
@Serializable
data class UpdateGroupRequest(
    val name: String,
    val leagueIds: List<Int>,
    val teamIds: List<Int>,
)

@Serializable
data class JoinGroupRequest(val code: String)

/**
 * One fixture as the group sees it.
 *
 * [predictions] is empty until the match kicks off. That emptiness is the mechanism, not
 * a rendering choice: the server does not send other people's guesses before then, so
 * there is nothing here for a determined client to reveal.
 */
@Serializable
data class GroupFixtureJson(
    val match: MatchJson,
    val locked: Boolean = false,
    val myPrediction: PredictionJson? = null,
    val predictions: List<PredictionJson> = emptyList(),
)

@Serializable
data class GroupFixtureListJson(val fixtures: List<GroupFixtureJson> = emptyList())

@Serializable
data class PredictionJson(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val home: Int,
    val away: Int,
    /** All three stay absent until the fixture is settled. */
    val points: Int? = null,
    val exact: Boolean? = null,
    val correctOutcome: Boolean? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class SubmitPredictionRequest(val home: Int, val away: Int)

@Serializable
data class LeaderboardJson(
    val groupId: Long = 0,
    /** The scoring rule, sent rather than assumed, so the screen never contradicts it. */
    val exactPoints: Int = 3,
    val outcomePoints: Int = 1,
    /** Whoever created the group. Marked on the table even before a ball is kicked. */
    val captainUserId: String? = null,
    /**
     * The whole rulebook, as the server actually scores with it.
     *
     * Absent on an older server, which is why the screen treats a null as "show nothing"
     * rather than as "show zeroes": a rules sheet full of noughts is worse than no sheet.
     */
    val rules: RulebookJson? = null,
    val entries: List<LeaderboardEntryJson> = emptyList(),
)

@Serializable
data class RulebookJson(
    val scoring: List<ScoringRowJson> = emptyList(),
    val multipliers: List<MultiplierRowJson> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class ScoringRowJson(val label: String = "", val points: Int = 0)

@Serializable
data class MultiplierRowJson(
    val stage: String = "",
    val label: String = "",
    val multiplier: Int = 1,
)

@Serializable
data class LeaderboardEntryJson(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val points: Int = 0,
    val exactCount: Int = 0,
    /** Includes the exact ones: an exact score is also the right direction. */
    val correctOutcomeCount: Int = 0,
    val settledCount: Int = 0,
    val rank: Int = 0,
)

@Serializable
data class ChatMessageJson(
    val id: Long,
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val text: String,
    val createdAt: Long,
)

@Serializable
data class ChatListJson(
    val messages: List<ChatMessageJson> = emptyList(),
    val since: Long = 0,
)

@Serializable
data class SendChatRequest(val text: String)

@Serializable
data class ProfileJson(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val avatarUrl: String? = null,
)
