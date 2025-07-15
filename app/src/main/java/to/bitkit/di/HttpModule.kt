package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingConfig
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import to.bitkit.utils.Logger
import javax.inject.Qualifier
import javax.inject.Singleton
import io.ktor.client.plugins.logging.Logger as KtorLogger

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProtoClient

@Module
@InstallIn(SingletonComponent::class)
object HttpModule {

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient {
        return HttpClient {
            install(HttpTimeout) {
                this@install.defaultTimeoutConfig()
            }
            install(Logging) {
                this@install.defaultLoggingConfig()
            }
            install(ContentNegotiation) {
                json(json = json)
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }

    @ProtoClient
    @Provides
    @Singleton
    fun provideProtoHttpClient(): HttpClient {
        return HttpClient {
            install(HttpTimeout) {
                this@install.defaultTimeoutConfig()
            }
            install(Logging) {
                this@install.defaultLoggingConfig()
            }
        }
    }

    private fun HttpTimeoutConfig.defaultTimeoutConfig() {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }

    private fun LoggingConfig.defaultLoggingConfig() {
        logger = KtorLogger.APP
        level = LogLevel.NONE
    }
}

private val KtorLogger.Companion.APP
    get() = object : KtorLogger {
        override fun log(message: String) {
            Logger.debug(message)
        }
    }
