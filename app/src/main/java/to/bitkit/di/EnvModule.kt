@file:Suppress("unused")

package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Module
@InstallIn(SingletonComponent::class)
object EnvModule {

    @Provides
    fun provideNetwork(): Network {
        return Env.network
    }

    @OptIn(ExperimentalTime::class)
    @Provides
    fun provideClock(): Clock {
        return Clock.System
    }
}
