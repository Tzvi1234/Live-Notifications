package com.tzvi.kickoff.data.backend

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** REST surface of the matchUP backend deployed on Render. */
interface KickoffBackendService {

    @GET("v1/health")
    suspend fun health(): HealthJson

    @GET("v1/leagues")
    suspend fun leagues(@Query("featured") featured: Boolean? = null): LeagueListJson

    @GET("v1/teams")
    suspend fun teams(
        @Query("league") league: Int? = null,
        @Query("season") season: Int? = null,
        @Query("q") query: String? = null,
    ): TeamListJson

    @GET("v1/fixtures")
    suspend fun fixtures(
        @Query("date") date: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("teams") teamIds: String? = null,
        @Query("leagues") leagueIds: String? = null,
    ): MatchListJson

    @GET("v1/fixtures/live")
    suspend fun liveFixtures(@Query("teams") teamIds: String? = null): MatchListJson

    @GET("v1/matches/{id}")
    suspend fun match(@Path("id") id: Long): MatchDetailJson

    /**
     * The endpoints below exist so the backend path is not a poorer app than the direct
     * one. Squads, player profiles, per-fixture player lines and pre-match predictions
     * used to be reachable only with a personal API key, which meant the feature simply
     * vanished for anyone who did the sensible thing and pointed at the server.
     */
    @GET("v1/teams/{id}/fixtures")
    suspend fun teamFixtures(
        @Path("id") teamId: Int,
        @Query("last") last: Int? = null,
        @Query("next") next: Int? = null,
    ): MatchListJson

    @GET("v1/teams/{id}/squad")
    suspend fun squad(@Path("id") teamId: Int): SquadJson

    @GET("v1/players/{id}")
    suspend fun player(@Path("id") playerId: Int): PlayerProfileJson

    @GET("v1/matches/{id}/players")
    suspend fun matchPlayers(@Path("id") matchId: Long): MatchPlayersJson

    @GET("v1/matches/{id}/predictions")
    suspend fun predictions(@Path("id") matchId: Long): PredictionJson

    @GET("v1/config")
    suspend fun config(): ServerConfigJson

    @POST("v1/devices")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): RegisterDeviceResponse

    @DELETE("v1/devices/{token}")
    suspend fun unregisterDevice(@Path("token") token: String)

    @PUT("v1/subscriptions")
    suspend fun updateSubscriptions(@Body body: SubscriptionRequest)
}
