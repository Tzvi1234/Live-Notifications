package com.tzvi.kickoff.data.remote.api

import com.tzvi.kickoff.data.remote.dto.ApiEnvelope
import com.tzvi.kickoff.data.remote.dto.EventResponse
import com.tzvi.kickoff.data.remote.dto.FixturePlayersResponse
import com.tzvi.kickoff.data.remote.dto.FixtureResponse
import com.tzvi.kickoff.data.remote.dto.PredictionResponse
import com.tzvi.kickoff.data.remote.dto.LeagueCatalogueResponse
import com.tzvi.kickoff.data.remote.dto.LineupResponse
import com.tzvi.kickoff.data.remote.dto.PlayerProfileResponse
import com.tzvi.kickoff.data.remote.dto.SquadResponse
import com.tzvi.kickoff.data.remote.dto.StatisticsResponse
import com.tzvi.kickoff.data.remote.dto.TeamCatalogueResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * API-Football v3, called directly from the device.
 *
 * This is the "bring your own key" path: it works with nothing deployed, but a free
 * key is 100 requests/day, which one live match at a 60s cadence already exceeds.
 * Production traffic should go through the matchUP backend instead, which polls once
 * on behalf of every user and pushes deltas.
 */
interface ApiFootballService {

    @GET("leagues")
    suspend fun leagues(
        @Query("id") id: Int? = null,
        @Query("country") country: String? = null,
        @Query("season") season: Int? = null,
        @Query("current") current: String? = null,
        @Query("search") search: String? = null,
    ): ApiEnvelope<LeagueCatalogueResponse>

    @GET("teams")
    suspend fun teams(
        @Query("id") id: Int? = null,
        @Query("league") league: Int? = null,
        @Query("season") season: Int? = null,
        @Query("search") search: String? = null,
    ): ApiEnvelope<TeamCatalogueResponse>

    @GET("fixtures")
    suspend fun fixtures(
        @Query("id") id: Long? = null,
        @Query("date") date: String? = null,
        @Query("league") league: Int? = null,
        @Query("season") season: Int? = null,
        @Query("team") team: Int? = null,
        @Query("next") next: Int? = null,
        @Query("last") last: Int? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("timezone") timezone: String? = null,
    ): ApiEnvelope<FixtureResponse>

    /**
     * Every in-play fixture in one request - the cheapest possible live poll.
     * Pass `all`, or dash-separated league ids to narrow it ("39-140-135").
     */
    @GET("fixtures")
    suspend fun liveFixtures(
        @Query("live") live: String = "all",
    ): ApiEnvelope<FixtureResponse>

    /**
     * The provider's own read on a fixture: win percentages, form and a one-line call.
     *
     * Takes a fixture and nothing else - there is no way to ask for several at once, so
     * this is one request per match and is worth caching until kick-off. Recomputed
     * hourly at the source, so polling it faster than that buys nothing.
     */
    @GET("predictions")
    suspend fun predictions(@Query("fixture") fixtureId: Long): ApiEnvelope<PredictionResponse>

    /** Past meetings between two teams. The parameter really is "id-id". */
    @GET("fixtures/headtohead")
    suspend fun headToHead(
        @Query("h2h") h2h: String,
        @Query("last") last: Int? = null,
    ): ApiEnvelope<FixtureResponse>

    @GET("fixtures/events")
    suspend fun events(@Query("fixture") fixtureId: Long): ApiEnvelope<EventResponse>

    @GET("fixtures/lineups")
    suspend fun lineups(@Query("fixture") fixtureId: Long): ApiEnvelope<LineupResponse>

    @GET("fixtures/statistics")
    suspend fun statistics(@Query("fixture") fixtureId: Long): ApiEnvelope<StatisticsResponse>

    /**
     * Every player's line for one fixture, in one request.
     *
     * This is the whole player-detail feature on a 100-a-day key: photo, shirt number,
     * position, rating and the full stat block for all ~36 players come back together, so
     * tapping through from a line-up costs nothing beyond the fetch already made.
     */
    @GET("fixtures/players")
    suspend fun fixturePlayers(
        @Query("fixture") fixtureId: Long,
        @Query("team") teamId: Int? = null,
    ): ApiEnvelope<FixturePlayersResponse>

    /**
     * Identity only - birth, nationality, height, weight. No season parameter, unlike
     * `/players`, which is why this is the one used for a profile header.
     */
    /**
     * The current roster - the cheapest way to a full list of faces and shirt numbers,
     * and worth exactly one request per team because it changes a few times a season.
     */
    @GET("players/squads")
    suspend fun squad(@Query("team") teamId: Int): ApiEnvelope<SquadResponse>

    @GET("players/profiles")
    suspend fun playerProfile(
        @Query("player") playerId: Int,
    ): ApiEnvelope<PlayerProfileResponse>

    companion object {
        const val BASE_URL = "https://v3.football.api-sports.io/"
        const val API_KEY_HEADER = "x-apisports-key"

        /** Public crest CDN - no key, no referer check. */
        fun teamCrestUrl(teamId: Int) = "https://media.api-sports.io/football/teams/$teamId.png"
        fun leagueLogoUrl(leagueId: Int) = "https://media.api-sports.io/football/leagues/$leagueId.png"
        fun playerPhotoUrl(playerId: Int) = "https://media.api-sports.io/football/players/$playerId.png"
    }
}
