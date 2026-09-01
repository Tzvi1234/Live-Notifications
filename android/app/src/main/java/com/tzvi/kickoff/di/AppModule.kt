package com.tzvi.kickoff.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.tzvi.kickoff.BuildConfig
import com.tzvi.kickoff.data.local.KickoffDatabase
import com.tzvi.kickoff.data.local.dao.FavouriteTeamDao
import com.tzvi.kickoff.data.local.dao.FollowedLeagueDao
import com.tzvi.kickoff.data.local.dao.MatchDao
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.dao.TrackedActivityDao
import com.tzvi.kickoff.data.backend.KickoffBackendService
import com.tzvi.kickoff.data.remote.ApiFootballKeyInterceptor
import com.tzvi.kickoff.data.remote.BackendUrlInterceptor
import com.tzvi.kickoff.data.remote.ClerkAuthInterceptor
import com.tzvi.kickoff.data.remote.api.ApiFootballService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class FootballApi
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BackendApi
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ImageHttp

/** Plain client, no key or base-URL interceptor: for testing a value that is not saved yet. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ProbeHttp

/** The dispatcher for provider queries and disk work. Injected rather than hard-coded
 *  so a test can swap it for a deterministic one. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun loggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
        else HttpLoggingInterceptor.Level.NONE
    }

    @Provides
    @Singleton
    @ImageHttp
    fun imageHttpClient(logging: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @ProbeHttp
    fun probeHttpClient(logging: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @FootballApi
    fun footballHttpClient(
        keyInterceptor: ApiFootballKeyInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(keyInterceptor)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @BackendApi
    fun backendHttpClient(
        urlInterceptor: BackendUrlInterceptor,
        authInterceptor: ClerkAuthInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(urlInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun apiFootballService(
        @FootballApi client: OkHttpClient,
        json: Json,
    ): ApiFootballService = Retrofit.Builder()
        .baseUrl(ApiFootballService.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiFootballService::class.java)

    @Provides
    @Singleton
    fun backendService(
        @BackendApi client: OkHttpClient,
        json: Json,
    ): KickoffBackendService = Retrofit.Builder()
        .baseUrl(BackendUrlInterceptor.PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KickoffBackendService::class.java)

    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        @ImageHttp client: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            add(SvgDecoder.Factory())
        }
        .crossfade(true)
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): KickoffDatabase =
        Room.databaseBuilder(context, KickoffDatabase::class.java, KickoffDatabase.NAME)
            .addMigrations(KickoffDatabase.MIGRATION_1_2, KickoffDatabase.MIGRATION_2_3)
            // Still the last resort, but every version bump from here needs a real
            // migration first: the followed teams and leagues are the user's own choices,
            // not cache, and dropping them silently is not an upgrade.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun favouriteTeamDao(db: KickoffDatabase): FavouriteTeamDao = db.favouriteTeamDao()
    @Provides fun followedLeagueDao(db: KickoffDatabase): FollowedLeagueDao = db.followedLeagueDao()
    @Provides fun matchDao(db: KickoffDatabase): MatchDao = db.matchDao()
    @Provides fun matchEventDao(db: KickoffDatabase): MatchEventDao = db.matchEventDao()
    @Provides fun trackedActivityDao(db: KickoffDatabase): TrackedActivityDao = db.trackedActivityDao()
}
