@file:Suppress("unused")

package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import to.bitkit.repositories.LightningRepository
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.utils.AddressChecker
import javax.inject.Qualifier

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideLightningRepository(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        lightningService: LightningService,
        ldkNodeEventBus: LdkNodeEventBus,
        addressChecker: AddressChecker
    ): LightningRepository {
        return LightningRepository(
            bgDispatcher = bgDispatcher,
            lightningService = lightningService,
            ldkNodeEventBus = ldkNodeEventBus,
            addressChecker = addressChecker
        )
    }
}
