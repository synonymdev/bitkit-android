package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.ldk.LightningService

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class LightningModule {
    @Binds
    abstract fun bindLightningService(service: LightningService): LightningService
}
