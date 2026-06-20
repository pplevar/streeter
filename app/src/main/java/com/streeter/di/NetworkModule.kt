package com.streeter.di

import com.streeter.BuildConfig
import com.streeter.data.remote.api.StreeterApiService
import com.streeter.data.remote.auth.SyncAuthTokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Shared Ktor client configuration for the Streeter sync API (issue #17, ADR-0002).
 *
 * [tokenProvider] is read on every request: when it returns a non-blank token an
 * `Authorization: Bearer <token>` header is attached; a blank token omits the header entirely so
 * the server's PERMISSIVE/STRICT policy decides. Deliberately does **not** use the Ktor `Auth`
 * plugin — its token caching fights a user-editable static token.
 */
fun HttpClientConfig<*>.configureStreeterClient(
    tokenProvider: () -> String,
    debug: Boolean,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Logging) {
        level = if (debug) LogLevel.BODY else LogLevel.NONE
    }
    defaultRequest {
        val token = tokenProvider()
        if (token.isNotBlank()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideHttpClient(tokenStore: SyncAuthTokenStore): HttpClient =
        HttpClient(Android) {
            configureStreeterClient(tokenProvider = { tokenStore.token }, debug = BuildConfig.DEBUG)
        }

    @Provides
    @Singleton
    fun provideStreeterApiService(client: HttpClient): StreeterApiService = StreeterApiService(client, BuildConfig.STREETER_BASE_URL)
}
