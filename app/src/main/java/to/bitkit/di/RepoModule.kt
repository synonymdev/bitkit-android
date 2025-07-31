package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object RepoModule {

    @Provides
    @Named("enablePolling")
    fun provideEnablePolling(): Boolean = true
}
