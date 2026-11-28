package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.PhoneSettingsParser
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.services.PhoneBackupReader
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Imports the phone's settings from its backup on VSS, which the watch can read because it holds the same seed. It
 * only reads: see [PhoneBackupReader]. The watch adopts the units, the currency and QuickPay, and sets none of them
 * itself.
 */
@Singleton
class SettingsSyncRepo internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val seedStore: SeedStore,
    private val backupReader: PhoneBackupReader,
    private val settingsStore: SettingsStore,
    private val sparkService: WatchSparkService,
    private val walletRepo: WalletRepo,
    private val nowMs: () -> Long,
) {
    companion object {
        private const val TAG = "SettingsSyncRepo"

        /** The key of the phone's settings backup, the name of its `BackupCategory.SETTINGS`. */
        const val SETTINGS_KEY = "SETTINGS"

        /** How long signing in to VSS and reading the backup may take. */
        val READ_TIMEOUT = 30.seconds
    }

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        seedStore: SeedStore,
        backupReader: PhoneBackupReader,
        settingsStore: SettingsStore,
        sparkService: WatchSparkService,
        walletRepo: WalletRepo,
    ) : this(
        ioDispatcher,
        seedStore,
        backupReader,
        settingsStore,
        sparkService,
        walletRepo,
        System::currentTimeMillis,
    )

    private val mutex = Mutex()

    /**
     * Reads the phone's settings backup and adopts it, for a wallet that is ready. A backup that is missing, cannot
     * be read or has a newer layout keeps the watch's display values and turns QuickPay off. A read that fails, as
     * without a network, changes nothing: QuickPay then stops by itself once the last import is too old
     * ([QuickPayRepo.MAX_SETTINGS_AGE]). An import that a wallet wipe overtook is taken out again, so nothing of a
     * removed wallet's settings stays.
     */
    suspend fun sync(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { syncLocked() } }
            .onFailure { Logger.warn("Failed to import the phone's settings", it, context = TAG) }
    }

    private suspend fun syncLocked() {
        val wipeToken = sparkService.wipeToken() ?: throw SparkError.WipeInProgress()
        if (walletRepo.walletState.value.status != WalletStatus.Ready) throw SettingsSyncError.NotReady()
        val seed = seedStore.load().getOrThrow()
        val backup = withTimeoutOrNull(READ_TIMEOUT) { Result.success(backupReader.read(seed, SETTINGS_KEY)) }
            ?: throw SettingsSyncError.Timeout()
        // From here the outcome is written whole, also when the caller goes away.
        withContext(NonCancellable) {
            val bytes = backup.getOrThrow()
            val parsed = bytes?.let { runCatching { PhoneSettingsParser.parse(it) } }
            when {
                parsed == null -> {
                    Logger.info("Found no settings backup of the phone", context = TAG)
                    settingsStore.clearQuickPay()
                }
                parsed.isFailure -> {
                    Logger.warn("Failed to read the phone's settings backup", parsed.exceptionOrNull(), context = TAG)
                    settingsStore.clearQuickPay()
                }
                else -> {
                    settingsStore.applyPhoneSettings(parsed.getOrThrow(), nowMs())
                    Logger.info("Imported the phone's settings", context = TAG)
                }
            }
            if (!sparkService.isCurrent(wipeToken)) {
                settingsStore.clearPhoneSettings()
                throw SparkError.WipeInProgress()
            }
        }
    }
}

sealed class SettingsSyncError(message: String) : AppError(message) {
    class NotReady : SettingsSyncError("The wallet is not ready")

    class Timeout : SettingsSyncError("Timed out reading the phone's settings")
}
