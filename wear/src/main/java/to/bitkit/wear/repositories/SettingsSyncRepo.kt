package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** How long an import waits for signing in to VSS and reading the backup. */
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

    /**
     * Where imports and their reads run. Nothing cancels it, so an import lands whoever stopped waiting for it, and
     * a read that was given up ends by itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val mutex = Mutex()

    /** The wipe token and the [clock] time of the last read that reached the backup. Guarded by [mutex]. */
    private var lastRead: Pair<Int, Long>? = null

    /**
     * Reads the phone's settings backup and adopts it, for a wallet that is ready. A backup that is missing, cannot
     * be decrypted or read, or has a newer layout keeps the watch's display values and turns QuickPay off. A read
     * that fails, as without a network, changes nothing: QuickPay goes on with the settings last imported. An import
     * that a wallet wipe overtook is taken out again, so nothing of a removed wallet's settings stays.
     *
     * The import runs in the repo's own scope and the caller only waits for it: a caller that is cancelled or
     * timed out returns at once, and the import goes on and lands for the next one to find. The import itself stops
     * waiting for its read after [READ_TIMEOUT], or as soon as the wallet stops being ready, as on a lock or a
     * pause, and then writes nothing. The VSS library blocks its thread for each of its HTTP calls, so a read that
     * was given up is not over at once: it ends with the call it is in, and only then is its client, with the keys
     * derived from the seed, shut down.
     *
     * A call that waited for another import joins it: within [MIN_READ_GAP] of a read of this wallet's backup
     * nothing is read again and the call succeeds, unless [isForced], as for the wearer's own tap.
     */
    suspend fun sync(isForced: Boolean = false): Result<Unit> = runSuspendCatching {
        val import = scope.async {
            runSuspendCatching { mutex.withLock { syncLocked(isForced) } }
                .onFailure { Logger.warn("Failed to import the phone's settings", it, context = TAG) }
        }
        import.await().getOrThrow()
    }

    private suspend fun syncLocked(isForced: Boolean) {
        val wipeToken = sparkService.wipeToken() ?: throw SparkError.WipeInProgress()
        if (walletRepo.walletState.value.status != WalletStatus.Ready) throw SettingsSyncError.NotReady()
        if (!isForced && isReadRecently(wipeToken)) return
        val seed = seedStore.load().getOrThrow()
        val backup = withTimeoutOrNull(READ_TIMEOUT) { readWhileReady(seed) } ?: throw SettingsSyncError.Timeout()
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

    private fun isReadRecently(wipeToken: Int): Boolean {
        val (token, readAtMs) = lastRead ?: return false
        return token == wipeToken && clock.nowMs() - readAtMs in 0 until MIN_READ_GAP.inWholeMilliseconds
    }

    /**
     * Reads the settings backup, or fails with [SettingsSyncError.NotReady] the moment the wallet is not ready any
     * more. The read runs apart in [scope], since it blocks its thread and cannot be interrupted: this returns as
     * soon as either side answers or the caller's time is up, and a read that lost is cancelled, which it follows
     * at the end of its HTTP call, shutting its client down.
     */
    private suspend fun readWhileReady(seed: WalletSeed): Result<ByteArray?> {
        val read = scope.async { runSuspendCatching { backupReader.read(seed, SETTINGS_KEY) } }
        try {
            return channelFlow {
                launch { send(read.await()) }
                launch {
                    walletRepo.walletState.first { it.status != WalletStatus.Ready }
                    send(Result.failure(SettingsSyncError.NotReady()))
                }
            }.first()
        } finally {
            read.cancel()
        }
    }
}

sealed class SettingsSyncError(message: String) : AppError(message) {
    class NotReady : SettingsSyncError("The wallet is not ready")

    class Timeout : SettingsSyncError("Timed out reading the phone's settings")
}
