package to.bitkit.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import to.bitkit.data.AppDb
import to.bitkit.data.dao.TransferDao
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DispatchersModule::class, DbModule::class]
)
object TestModule {

    @Provides
    @Singleton
    @UiDispatcher
    fun provideUiDispatcher(): CoroutineDispatcher = UnconfinedTestDispatcher()

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = UnconfinedTestDispatcher()

    @Provides
    @Singleton
    @BgDispatcher
    fun provideBgDispatcher(): CoroutineDispatcher = UnconfinedTestDispatcher()

    @Provides
    @Singleton
    fun provideAppDb(
        @ApplicationContext context: Context,
    ): AppDb {
        return Room.inMemoryDatabaseBuilder(
            context,
            AppDb::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @Provides
    @Singleton
    fun provideTransferDao(db: AppDb): TransferDao = db.transferDao()
}
