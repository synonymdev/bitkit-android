@file:Suppress("unused")

package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env

@Module
@InstallIn(SingletonComponent::class)
object EnvModule {

    @Provides
    fun provideNetwork(): Network {
        return Env.network
    }
}
