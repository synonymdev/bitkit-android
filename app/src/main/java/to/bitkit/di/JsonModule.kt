package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import to.bitkit.data.dto.TransactionMetadata
import javax.inject.Singleton

val json = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(TransactionMetadata::class) {
            subclass(TransactionMetadata.OnChainActivity::class)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object JsonModule {
    @Provides
    @Singleton
    fun provideJson(): Json {
        return json
    }
}
