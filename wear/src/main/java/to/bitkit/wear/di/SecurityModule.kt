package to.bitkit.wear.di

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import to.bitkit.wear.data.AdapterNfcMonitor
import to.bitkit.wear.data.ConnectivityNetworkMonitor
import to.bitkit.wear.data.ElapsedClock
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.data.NfcMonitor
import to.bitkit.wear.data.OwnInvoiceStore
import to.bitkit.wear.data.ProcessForegroundMonitor
import to.bitkit.wear.data.QuickPaySpendStore
import to.bitkit.wear.data.SettingsOwnInvoiceStore
import to.bitkit.wear.data.SettingsQuickPaySpendStore
import to.bitkit.wear.data.SettingsUncertainSendStore
import to.bitkit.wear.data.UncertainSendStore
import to.bitkit.wear.data.WipeMarker
import to.bitkit.wear.data.settingsDataStore
import to.bitkit.wear.env.Env
import to.bitkit.wear.security.AndroidKeystoreSeedKey
import to.bitkit.wear.security.Bip39
import to.bitkit.wear.security.CoreBip39
import to.bitkit.wear.security.DeviceLock
import to.bitkit.wear.security.KeyguardDeviceLock
import to.bitkit.wear.security.KeystoreSeedStore
import to.bitkit.wear.security.SeedKey
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.services.CoreInputDecoder
import to.bitkit.wear.services.InputDecoder
import to.bitkit.wear.services.PhoneBackupReader
import to.bitkit.wear.services.SelfSendGuard
import to.bitkit.wear.services.VssPhoneBackupReader
import to.bitkit.wear.services.WalletSelfSendGuard
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    @Singleton
    abstract fun bindSeedKey(key: AndroidKeystoreSeedKey): SeedKey

    @Binds
    @Singleton
    abstract fun bindDeviceLock(lock: KeyguardDeviceLock): DeviceLock

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(monitor: ConnectivityNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindNfcMonitor(monitor: AdapterNfcMonitor): NfcMonitor

    @Binds
    @Singleton
    abstract fun bindForegroundMonitor(monitor: ProcessForegroundMonitor): ForegroundMonitor

    @Binds
    @Singleton
    abstract fun bindBip39(bip39: CoreBip39): Bip39

    @Binds
    @Singleton
    abstract fun bindInputDecoder(decoder: CoreInputDecoder): InputDecoder

    @Binds
    @Singleton
    abstract fun bindOwnInvoiceStore(store: SettingsOwnInvoiceStore): OwnInvoiceStore

    @Binds
    @Singleton
    abstract fun bindUncertainSendStore(store: SettingsUncertainSendStore): UncertainSendStore

    @Binds
    @Singleton
    abstract fun bindSelfSendGuard(guard: WalletSelfSendGuard): SelfSendGuard

    @Binds
    @Singleton
    abstract fun bindQuickPaySpendStore(store: SettingsQuickPaySpendStore): QuickPaySpendStore

    @Binds
    @Singleton
    abstract fun bindPhoneBackupReader(reader: VssPhoneBackupReader): PhoneBackupReader

    companion object {
        private const val SEED_DIR = "seed"
        private const val SETTINGS_NAME = "settings"

        /**
         * The seed file lives in the no-backup directory, on top of the app's backups being off. The directory is
         * resolved on first use, since asking the context for it reads the disk.
         */
        private fun seedDirectory(context: Context): Lazy<File> = lazy { File(context.noBackupFilesDir, SEED_DIR) }

        /** Debug builds may keep the seed key in software, which is all the emulators offer. */
        @Provides
        @Singleton
        fun provideSeedStore(
            @ApplicationContext context: Context,
            key: SeedKey,
            deviceLock: DeviceLock,
        ): SeedStore = KeystoreSeedStore(seedDirectory(context), key, deviceLock, isSoftwareKeyAllowed = Env.isDebug)

        @Provides
        @Singleton
        fun provideElapsedClock(): ElapsedClock = ElapsedClock { SystemClock.elapsedRealtime() }

        @Provides
        @Singleton
        fun provideWipeMarker(@ApplicationContext context: Context): WipeMarker = WipeMarker(seedDirectory(context))

        @Provides
        @Singleton
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = settingsDataStore(CoroutineScope(ioDispatcher + SupervisorJob())) {
            context.preferencesDataStoreFile(SETTINGS_NAME)
        }
    }
}
