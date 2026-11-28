package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.ElapsedClock
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.PhoneSettingsParser
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.PhoneBackupReader
import to.bitkit.wear.services.PhoneBackupUnreadable
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
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
    private val clock: ElapsedClock,
    private val nowMs: () -> Long,
) {
    companion object {
        private const val TAG = "SettingsSyncRepo"

        /** The key of the phone's settings backup, the name of its `BackupCategory.SETTINGS`. */
        const val SETTINGS_KEY = "SETTINGS"

        /** How long signing in to VSS and reading the backup may take. */
        val READ_TIMEOUT = 30.seconds

        /**
         * How soon after a read of the backup the next one is skipped, unless the wearer asked for it. Each read
         * signs in to VSS anew, so a wallet that keeps losing and finding its network must not start one each time.
         */
        val MIN_READ_GAP = 1.minutes
    }

    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        seedStore: SeedStore,
        backupReader: PhoneBackupReader,
        settingsStore: SettingsStore,
        sparkService: WatchSparkService,
        walletRepo: WalletRepo,
        clock: ElapsedClock,
    ) : this(
        ioDispatcher,
        seedStore,
        backupReader,
        settingsStore,
        sparkService,
        walletRepo,
        clock,
        System::currentTimeMillis,
    )

    private val mutex = Mutex()

    /** The wipe token and the [clock] time of the last read that reached the backup. Guarded by [mutex]. */
    private var lastRead: Pair<Int, Long>? = null

    /**
     * Reads the phone's settings backup and adopts it, for a wallet that is ready. A backup that is missing, cannot
     * be decrypted or read, or has a newer layout keeps the watch's display values and turns QuickPay off. A read
     * that fails, as without a network, changes nothing: QuickPay then stops by itself once the last import is too
     * old ([QuickPayRepo.MAX_SETTINGS_AGE]). The read is given up as soon as the wallet stops being ready, as on a
     * lock or a pause, so nothing derived from the seed outlives either. An import that a wallet wipe overtook is
     * taken out again, so nothing of a removed wallet's settings stays.
     *
     * A call that waited for another import joins it: within [MIN_READ_GAP] of a read of this wallet's backup
     * nothing is read again and the call succeeds, unless [isForced], as for the wearer's own tap.
     */
    suspend fun sync(isForced: Boolean = false): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { syncLocked(isForced) } }
            .onFailure { Logger.warn("Failed to import the phone's settings", it, context = TAG) }
    }

    private suspend fun syncLocked(isForced: Boolean) {
        val wipeToken = sparkService.wipeToken() ?: throw SparkError.WipeInProgress()
        if (walletRepo.walletState.value.status != WalletStatus.Ready) throw SettingsSyncError.NotReady()
        if (!isForced && isReadRecently(wipeToken)) return
        val seed = seedStore.load().getOrThrow()
        val backup = withTimeoutOrNull(READ_TIMEOUT) { readWhileReady(seed) } ?: throw SettingsSyncError.Timeout()
        // From here the outcome is written whole, also when the caller goes away.
        withContext(NonCancellable) {
            val parsed = backup.fold(
                onSuccess = { bytes -> runSuspendCatching { bytes?.let { PhoneSettingsParser.parse(it) } } },
                onFailure = { if (it is PhoneBackupUnreadable) Result.failure(it) else throw it },
            )
            val settings = parsed.getOrNull()
            when {
                parsed.isFailure -> {
                    Logger.warn("Failed to read the phone's settings backup", parsed.exceptionOrNull(), context = TAG)
                    settingsStore.clearQuickPay()
                }
                settings == null -> {
                    Logger.info("Found no settings backup of the phone", context = TAG)
                    settingsStore.clearQuickPay()
                }
                else -> {
                    settingsStore.applyPhoneSettings(settings, nowMs())
                    Logger.info("Imported the phone's settings", context = TAG)
                }
            }
            lastRead = wipeToken to clock.nowMs()
            if (!sparkService.isCurrent(wipeToken)) {
                settingsStore.clearPhoneSettings()
                throw SparkError.WipeInProgress()
            }
        }
    }

    private fun isReadRecently(wipeToken: Int): Boolean {
        val (token, readAtMs) = lastRead ?: return false
        return token == wipeToken && clock.nowMs() - readAtMs in 0 until MIN_READ_GAP.inWholeMilliseconds
    }

    /**
     * Reads the settings backup, or fails with [SettingsSyncError.NotReady] the moment the wallet is not ready any
     * more. Either way the reader has shut its client down when this returns.
     */
    private suspend fun readWhileReady(seed: WalletSeed): Result<ByteArray?> = channelFlow {
        launch { send(runSuspendCatching { backupReader.read(seed, SETTINGS_KEY) }) }
        launch {
            walletRepo.walletState.first { it.status != WalletStatus.Ready }
            send(Result.failure(SettingsSyncError.NotReady()))
        }
    }.first()
}

sealed class SettingsSyncError(message: String) : AppError(message) {
    class NotReady : SettingsSyncError("The wallet is not ready")

    class Timeout : SettingsSyncError("Timed out reading the phone's settings")
}
