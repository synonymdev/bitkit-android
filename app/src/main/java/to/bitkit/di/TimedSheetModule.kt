package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import to.bitkit.utils.timedsheets.TimedSheetManager

@Module
@InstallIn(SingletonComponent::class)
object TimedSheetModule {

    @Provides
    fun provideTimedSheetManagerProvider(): (CoroutineScope) -> TimedSheetManager {
        return ::TimedSheetManager
    }
}
