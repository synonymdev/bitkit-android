package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.services.BlocktankService
import to.bitkit.ui.SharedViewModel
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewModelModule {
    @Singleton
    @Provides
    fun provideSharedViewModel(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        blocktankService: BlocktankService,
    ): SharedViewModel {
        return SharedViewModel(
            bgDispatcher,
            blocktankService,
        )
    }
}
