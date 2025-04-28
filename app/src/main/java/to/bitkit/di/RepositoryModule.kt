@file:Suppress("unused")

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
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.utils.AddressChecker

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideLightningRepository(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        lightningService: LightningService,
        ldkNodeEventBus: LdkNodeEventBus,
        addressChecker: AddressChecker
    ): LightningRepo {
        return LightningRepo(
            bgDispatcher = bgDispatcher,
            lightningService = lightningService,
            ldkNodeEventBus = ldkNodeEventBus,
            addressChecker = addressChecker
        )
    }

    @Provides
    fun provideWalletRepository(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        @ApplicationContext appContext: Context,
        appStorage: AppStorage,
        db: AppDb,
        keychain: Keychain,
        coreService: CoreService,
        blocktankNotificationsService: BlocktankNotificationsService,
        firebaseMessaging: FirebaseMessaging,
        settingsStore: SettingsStore,
    ): WalletRepo {
        return WalletRepo(
            bgDispatcher = bgDispatcher,
            appContext = appContext,
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            blocktankNotificationsService = blocktankNotificationsService,
            firebaseMessaging = firebaseMessaging,
            settingsStore = settingsStore
        )
    }
}
