package com.tzvi.kickoff.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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
    version = 2,
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

        /**
         * Adds the per-competition coverage flags to the followed leagues.
         *
         * Written out rather than left to a destructive fallback because this table
         * holds a choice the user made during onboarding - wiping it to add seven
         * booleans would send them back through the picker for nothing. The defaults
         * match [com.tzvi.kickoff.core.model.LeagueCoverage]: optimistic, and corrected
         * the next time the catalogue is fetched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "coversLineups",
                    "coversEvents",
                    "coversFixtureStats",
                    "coversPlayerStats",
                    "coversStandings",
                    "coversInjuries",
                    "coversPredictions",
                ).forEach { column ->
                    connection.execSQL(
                        "ALTER TABLE followed_leagues ADD COLUMN $column INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }
        }
    }
}
