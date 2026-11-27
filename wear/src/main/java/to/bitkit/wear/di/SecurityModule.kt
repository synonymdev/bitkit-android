package to.bitkit.wear.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import to.bitkit.wear.data.ConnectivityNetworkMonitor
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.security.AndroidKeystoreSeedKey
import to.bitkit.wear.security.Bip39
import to.bitkit.wear.security.CoreBip39
import to.bitkit.wear.security.DeviceLock
import to.bitkit.wear.security.KeyguardDeviceLock
import to.bitkit.wear.security.KeystoreSeedStore
import to.bitkit.wear.security.SeedKey
import to.bitkit.wear.security.SeedStore
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
    abstract fun bindBip39(bip39: CoreBip39): Bip39

    companion object {
        private const val SEED_DIR = "seed"

        /** The seed file lives in the no-backup directory, on top of the app's backups being off. */
        @Provides
        @Singleton
        fun provideSeedStore(
            @ApplicationContext context: Context,
            key: SeedKey,
            deviceLock: DeviceLock,
        ): SeedStore = KeystoreSeedStore(File(context.noBackupFilesDir, SEED_DIR), key, deviceLock)
    }
}
