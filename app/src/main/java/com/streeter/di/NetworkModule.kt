package com.streeter.di

import com.streeter.BuildConfig
import com.streeter.data.remote.api.StreeterApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient =
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Logging) {
                level = if (BuildConfig.DEBUG) LogLevel.BODY else LogLevel.NONE
            }
        }

    @Provides
    @Singleton
    fun provideStreeterApiService(client: HttpClient): StreeterApiService = StreeterApiService(client, BuildConfig.STREETER_BASE_URL)
}
