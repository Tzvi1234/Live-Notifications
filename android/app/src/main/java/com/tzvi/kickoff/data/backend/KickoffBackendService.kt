package com.tzvi.kickoff.data.backend

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** REST surface of the Kickoff backend deployed on Render. */
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

    @POST("v1/devices")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): RegisterDeviceResponse

    @DELETE("v1/devices/{token}")
    suspend fun unregisterDevice(@Path("token") token: String)

    @PUT("v1/subscriptions")
    suspend fun updateSubscriptions(@Body body: SubscriptionRequest)
}
