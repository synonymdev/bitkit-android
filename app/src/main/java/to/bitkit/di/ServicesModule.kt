@file:Suppress("UNUSED_PARAMETER")

package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService

@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {
    @Provides
    fun provideLightningService(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
    ): LightningService {
        return LightningService.shared
    }

    @Provides
    fun provideBitcoinService(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
    ): OnChainService {
        return OnChainService.shared
    }
}
