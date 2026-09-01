package com.tzvi.kickoff.data.predict

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The authenticated half of the matchUP backend.
 *
 * Separate from the football service because every call here needs a signed-in user and
 * none of the football ones do: keeping them apart is what lets the app stay fully usable
 * without an account.
 */
interface PredictService {

    @GET("v1/me")
    suspend fun profile(): ProfileJson

    @PATCH("v1/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): ProfileJson

    @GET("v1/groups")
    suspend fun groups(): GroupListJson

    @POST("v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): GroupJson

    @GET("v1/groups/{id}")
    suspend fun group(@Path("id") groupId: Long): GroupJson

    @PATCH("v1/groups/{id}")
    suspend fun updateGroup(
        @Path("id") groupId: Long,
        @Body body: UpdateGroupRequest,
    ): GroupJson

    @DELETE("v1/groups/{id}")
    suspend fun deleteGroup(@Path("id") groupId: Long)

    @POST("v1/groups/join")
    suspend fun joinGroup(@Body body: JoinGroupRequest): GroupJson

    @DELETE("v1/groups/{id}/members/me")
    suspend fun leaveGroup(@Path("id") groupId: Long)

    @GET("v1/groups/{id}/fixtures")
    suspend fun fixtures(@Path("id") groupId: Long): GroupFixtureListJson

    @PUT("v1/groups/{id}/predictions/{matchId}")
    suspend fun submitPrediction(
        @Path("id") groupId: Long,
        @Path("matchId") matchId: Long,
        @Body body: SubmitPredictionRequest,
    ): PredictionJson

    @GET("v1/groups/{id}/leaderboard")
    suspend fun leaderboard(@Path("id") groupId: Long): LeaderboardJson

    @GET("v1/groups/{id}/chat")
    suspend fun chat(@Path("id") groupId: Long): ChatListJson

    @POST("v1/groups/{id}/chat")
    suspend fun sendChat(@Path("id") groupId: Long, @Body body: SendChatRequest): ChatMessageJson
}
