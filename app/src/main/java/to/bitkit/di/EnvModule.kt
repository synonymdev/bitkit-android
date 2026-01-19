package to.bitkit.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Module
@InstallIn(SingletonComponent::class)
object EnvModule {

    @Provides
    fun provideNetwork(): Network = Env.network

    @OptIn(ExperimentalTime::class)
    @Provides
    fun provideClock(): Clock = Clock.System

    @Provides
    fun provideLocale(@ApplicationContext context: Context): Locale = context.resources.configuration.locales[0]
}
