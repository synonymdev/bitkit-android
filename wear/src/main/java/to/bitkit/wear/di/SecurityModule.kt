package to.bitkit.wear.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.UnimplementedSeedStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    /** Binds the placeholder until the Keystore-backed [SeedStore] replaces it. */
    @Binds
    @Singleton
    abstract fun bindSeedStore(store: UnimplementedSeedStore): SeedStore
}
