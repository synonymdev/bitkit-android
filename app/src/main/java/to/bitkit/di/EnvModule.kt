package to.bitkit.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env
import to.bitkit.utils.DemoClock
import java.util.Locale
import javax.inject.Qualifier
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
    @SubscriptionClock
    fun provideSubscriptionClock(clock: Clock): Clock = DemoClock.subscriptionClock(clock)

    @Provides
    fun provideLocale(@ApplicationContext context: Context): Locale = context.resources.configuration.locales[0]
}

/** The [Clock] for Paykit subscription scheduling; [DemoClock] can move it ahead in debug builds. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SubscriptionClock
