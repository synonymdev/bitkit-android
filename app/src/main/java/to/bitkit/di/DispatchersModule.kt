@file:Suppress("unused")

package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UiDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BgDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @UiDispatcher
    @Provides
    fun provideUiDispatcher(): CoroutineDispatcher {
        return Dispatchers.Main
    }

    @BgDispatcher
    @Provides
    fun provideBgDispatcher(): CoroutineDispatcher {
        return Dispatchers.Default
    }

    @IoDispatcher
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }
}
