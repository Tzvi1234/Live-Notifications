package com.tzvi.kickoff.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tzvi.kickoff.data.local.dao.FavouriteTeamDao
import com.tzvi.kickoff.data.local.dao.FollowedLeagueDao
import com.tzvi.kickoff.data.local.dao.MatchDao
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.local.entity.FavouriteTeamEntity
import com.tzvi.kickoff.data.local.entity.FollowedLeagueEntity
import com.tzvi.kickoff.data.local.entity.MatchEntity
import com.tzvi.kickoff.data.local.entity.MatchEventEntity
import com.tzvi.kickoff.data.local.entity.TrackedActivityEntity

@Database(
    entities = [
        FavouriteTeamEntity::class,
        FollowedLeagueEntity::class,
        MatchEntity::class,
        MatchEventEntity::class,
        TrackedActivityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KickoffDatabase : RoomDatabase() {
    abstract fun favouriteTeamDao(): FavouriteTeamDao
    abstract fun followedLeagueDao(): FollowedLeagueDao
    abstract fun matchDao(): MatchDao
    abstract fun matchEventDao(): MatchEventDao
    abstract fun trackedActivityDao(): TrackedActivityDao

    companion object {
        const val NAME = "kickoff.db"
    }
}
