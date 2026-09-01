package com.tzvi.kickoff.data.predict

import com.tzvi.kickoff.core.model.PredictGroup
import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.core.model.GroupMember
import com.tzvi.kickoff.core.model.ChatMessage
import com.tzvi.kickoff.core.model.PredictionEntry
import com.tzvi.kickoff.data.backend.BackendMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The prediction game, which is entirely the server's.
 *
 * Nothing here is cached in Room and that is deliberate. The two rules the game rests on
 * - you cannot change a guess after kick-off, and you cannot see anyone else's before it
 * - are only meaningful if the device is not the one deciding them. Holding a local copy
 * would invite exactly the drift that makes a leaderboard worth arguing with.
 */
@Singleton
class PredictRepository @Inject constructor(
    private val service: PredictService,
) {
    suspend fun groups(): List<PredictGroup> = io {
        service.groups().groups.map { it.toDomain() }
    }

    suspend fun createGroup(name: String, leagueIds: List<Int>, teamIds: List<Int>): PredictGroup =
        io { service.createGroup(CreateGroupRequest(name, leagueIds, teamIds)).toDomain() }

    /** Sends the whole selection: the server replaces its lists with these, never merges. */
    suspend fun updateGroup(
        groupId: Long,
        name: String,
        leagueIds: List<Int>,
        teamIds: List<Int>,
    ): PredictGroup = io {
        service.updateGroup(groupId, UpdateGroupRequest(name, leagueIds, teamIds)).toDomain()
    }

    /** The code is shouted across a room before it is typed, so case and spaces go. */
    suspend fun joinGroup(code: String): PredictGroup =
        io { service.joinGroup(JoinGroupRequest(code.filterNot { it.isWhitespace() })).toDomain() }

    suspend fun group(groupId: Long): PredictGroup = io { service.group(groupId).toDomain() }

    suspend fun leaveGroup(groupId: Long) = io { service.leaveGroup(groupId) }

    suspend fun deleteGroup(groupId: Long) = io { service.deleteGroup(groupId) }

    suspend fun fixtures(groupId: Long): List<GroupFixture> = io {
        service.fixtures(groupId).fixtures.map { it.toDomain() }
    }

    suspend fun predict(groupId: Long, matchId: Long, home: Int, away: Int) = io {
        service.submitPrediction(groupId, matchId, SubmitPredictionRequest(home, away))
    }

    suspend fun leaderboard(groupId: Long): List<GroupMember> = io {
        service.leaderboard(groupId).entries.map { it.toDomain() }
    }

    suspend fun chat(groupId: Long): List<ChatMessage> = io {
        service.chat(groupId).messages.map { it.toDomain() }
    }

    suspend fun sendChat(groupId: Long, text: String): ChatMessage = io {
        service.sendChat(groupId, SendChatRequest(text.trim())).toDomain()
    }

    suspend fun updateProfile(displayName: String?, avatarUrl: String?) = io {
        service.updateProfile(UpdateProfileRequest(displayName, avatarUrl))
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

private fun GroupJson.toDomain() = PredictGroup(
    id = id,
    name = name,
    inviteCode = inviteCode,
    isOwner = isOwner,
    memberCount = memberCount,
    leagueIds = leagueIds,
    teamIds = teamIds,
)

private fun LeaderboardEntryJson.toDomain() = GroupMember(
    userId = userId,
    displayName = displayName.orAnon(),
    avatarUrl = avatarUrl,
    points = points,
    exactCount = exactCount,
    outcomeCount = correctOutcomeCount,
    settledCount = settledCount,
    rank = rank,
)

private fun PredictionJson.toDomain() = PredictionEntry(
    userId = userId,
    displayName = displayName.orAnon(),
    avatarUrl = avatarUrl,
    home = home,
    away = away,
    points = points,
)

private fun GroupFixtureJson.toDomain() = GroupFixture(
    match = BackendMapper.match(match),
    locked = locked,
    myPrediction = myPrediction?.toDomain(),
    others = predictions.map { it.toDomain() },
)

private fun ChatMessageJson.toDomain() = ChatMessage(
    id = id,
    userId = userId,
    displayName = displayName.orAnon(),
    avatarUrl = avatarUrl,
    text = text,
    sentAt = Instant.ofEpochSecond(createdAt),
)

/**
 * Somebody who signed up and never set a name.
 *
 * Their guesses still count and still have to be attributable on a leaderboard, so a
 * placeholder is the only workable answer - a blank row reads as a rendering bug.
 */
private fun String?.orAnon(): String = this?.takeIf { it.isNotBlank() } ?: "Someone"
