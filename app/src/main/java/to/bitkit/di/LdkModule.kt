package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.data.LdkSyncer
import to.bitkit.data.Syncer

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class LdkModule {
    @Binds
    abstract fun bindSyncer(ldkSyncer: LdkSyncer): Syncer
}