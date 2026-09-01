package com.tzvi.kickoff.data.predict

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.tzvi.kickoff.data.remote.BackendUrlInterceptor
import com.tzvi.kickoff.di.BackendApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Its own module rather than a few more lines in AppModule: this whole feature is
 * optional - it does nothing without a signed-in user and a server that has the game
 * enabled - and keeping its wiring together makes that boundary visible.
 */
@Module
@InstallIn(SingletonComponent::class)
object PredictModule {

    @Provides
    @Singleton
    fun predictService(
        @BackendApi client: OkHttpClient,
        json: Json,
    ): PredictService = Retrofit.Builder()
        // Same placeholder and the same interceptor as the football service: the real
        // address is only known at runtime and is rewritten per request.
        .baseUrl(BackendUrlInterceptor.PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PredictService::class.java)
}
