package to.bitkit.di

import com.google.firebase.messaging.FirebaseMessaging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.data.AppDb
import to.bitkit.data.BlocktankClient
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService
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
        appDb: AppDb,
        keychain: Keychain,
        blocktankService: BlocktankService,
        blocktankClient: BlocktankClient,
        onChainService: OnChainService,
        lightningService: LightningService,
    ): WalletViewModel {
        return WalletViewModel(
            uiDispatcher,
            bgDispatcher,
            appDb,
            keychain,
            blocktankService,
            blocktankClient,
            onChainService,
            lightningService,
            firebaseMessaging = FirebaseMessaging.getInstance(),
        )
    }
}
