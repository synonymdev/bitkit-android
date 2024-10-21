package to.bitkit.di

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.ui.WalletViewModel
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewModelModule {
    @Singleton
    @Provides
    fun provideSharedViewModel(
        @UiDispatcher uiDispatcher: CoroutineDispatcher,
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        @ApplicationContext context: Context,
        appDb: AppDb,
        keychain: Keychain,
        blocktankService: BlocktankService,
        lightningService: LightningService,
    ): WalletViewModel {
        return WalletViewModel(
            uiDispatcher,
            bgDispatcher,
            context,
            appDb,
            keychain,
            blocktankService,
            lightningService,
            firebaseMessaging = FirebaseMessaging.getInstance(),
        )
    }
}
