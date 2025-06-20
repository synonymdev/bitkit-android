package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock
import javax.inject.Named


@Module
@InstallIn(SingletonComponent::class)
object CurrencyModule {

    @Provides
    @Named("enablePolling")
    fun provideEnablePolling(): Boolean = true

    @Provides
    fun provideClock(): Clock {
        return Clock.System
    }
}
