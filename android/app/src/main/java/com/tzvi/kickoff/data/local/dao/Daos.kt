package com.tzvi.kickoff.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tzvi.kickoff.data.local.entity.FavouriteTeamEntity
import com.tzvi.kickoff.data.local.entity.FollowedLeagueEntity
import com.tzvi.kickoff.data.local.entity.MatchEntity
import com.tzvi.kickoff.data.local.entity.MatchEventEntity
import com.tzvi.kickoff.data.local.entity.TrackedActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteTeamDao {
    @Query("SELECT * FROM favourite_teams ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<FavouriteTeamEntity>>

    @Query("SELECT * FROM favourite_teams ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<FavouriteTeamEntity>

    @Query("SELECT teamId FROM favourite_teams")
    fun observeIds(): Flow<List<Int>>

    @Query("SELECT COUNT(*) FROM favourite_teams")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(team: FavouriteTeamEntity)

    @Upsert
    suspend fun upsertAll(teams: List<FavouriteTeamEntity>)

    @Query("DELETE FROM favourite_teams WHERE teamId = :teamId")
    suspend fun delete(teamId: Int)

    @Query("DELETE FROM favourite_teams")
    suspend fun clear()
}

@Dao
interface FollowedLeagueDao {
    @Query("SELECT * FROM followed_leagues ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<FollowedLeagueEntity>>

    @Query("SELECT * FROM followed_leagues ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<FollowedLeagueEntity>

    @Upsert
    suspend fun upsertAll(leagues: List<FollowedLeagueEntity>)

    @Query("DELETE FROM followed_leagues WHERE leagueId = :leagueId")
    suspend fun delete(leagueId: Int)
}

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun get(id: Long): MatchEntity?

    @Query("SELECT * FROM matches WHERE id = :id")
    fun observe(id: Long): Flow<MatchEntity?>

    @Query("SELECT * FROM matches WHERE kickoffAt BETWEEN :from AND :to ORDER BY kickoffAt ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<MatchEntity>>

    @Query(
        """
        SELECT * FROM matches
        WHERE (homeTeamId IN (:teamIds) OR awayTeamId IN (:teamIds))
          AND kickoffAt >= :from
        ORDER BY kickoffAt ASC
        LIMIT :limit
        """
    )
    fun observeUpcomingForTeams(teamIds: List<Int>, from: Long, limit: Int): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE phase IN (:livePhases) ORDER BY kickoffAt ASC")
    fun observeLive(livePhases: List<String>): Flow<List<MatchEntity>>

    @Query(
        """
        SELECT * FROM matches
        WHERE kickoffAt BETWEEN :from AND :to
          AND (homeTeamId IN (:teamIds) OR awayTeamId IN (:teamIds))
        ORDER BY kickoffAt ASC
        """
    )
    suspend fun windowForTeams(teamIds: List<Int>, from: Long, to: Long): List<MatchEntity>

    @Upsert
    suspend fun upsertAll(matches: List<MatchEntity>)

    @Upsert
    suspend fun upsert(match: MatchEntity)

    @Query("DELETE FROM matches WHERE kickoffAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM matches WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MatchEventDao {
    @Query("SELECT * FROM match_events WHERE matchId = :matchId ORDER BY minute ASC, extra ASC")
    fun observeForMatch(matchId: Long): Flow<List<MatchEventEntity>>

    @Query("SELECT * FROM match_events WHERE matchId = :matchId ORDER BY minute ASC, extra ASC")
    suspend fun forMatch(matchId: Long): List<MatchEventEntity>

    @Query("SELECT id FROM match_events WHERE matchId = :matchId")
    suspend fun idsForMatch(matchId: Long): List<String>

    /**
     * Inserts only rows we have never seen. The returned ids are exactly the events
     * that are new to this device and may therefore alert - the single dedupe gate
     * shared by polling and push.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(events: List<MatchEventEntity>): List<Long>

    @Transaction
    suspend fun insertNew(events: List<MatchEventEntity>): List<MatchEventEntity> {
        if (events.isEmpty()) return emptyList()
        val rowIds = insertIgnoring(events)
        return events.filterIndexed { index, _ -> rowIds.getOrElse(index) { -1L } != -1L }
    }

    @Query("UPDATE match_events SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<String>)

    @Query("DELETE FROM match_events WHERE matchId = :matchId")
    suspend fun clearForMatch(matchId: Long)

    @Query(
        "DELETE FROM match_events WHERE matchId IN " +
            "(SELECT id FROM matches WHERE kickoffAt < :before)"
    )
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface TrackedActivityDao {
    @Query("SELECT * FROM tracked_activities")
    fun observeAll(): Flow<List<TrackedActivityEntity>>

    @Query("SELECT * FROM tracked_activities WHERE dismissed = 0")
    suspend fun active(): List<TrackedActivityEntity>

    @Query("SELECT * FROM tracked_activities WHERE `key` = :key")
    suspend fun get(key: String): TrackedActivityEntity?

    @Upsert
    suspend fun upsert(activity: TrackedActivityEntity)

    @Query("UPDATE tracked_activities SET dismissed = 1 WHERE `key` = :key")
    suspend fun markDismissed(key: String)

    @Query("DELETE FROM tracked_activities WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM tracked_activities WHERE endsAt IS NOT NULL AND endsAt < :before")
    suspend fun deleteEndedBefore(before: Long)
}
