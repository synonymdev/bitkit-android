package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.security.DeviceLock
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Singleton
class WalletRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val seedStore: SeedStore,
    private val sparkService: WatchSparkService,
    private val settingsStore: SettingsStore,
    private val deviceLock: DeviceLock,
    private val networkMonitor: NetworkMonitor,
    private val foregroundMonitor: ForegroundMonitor,
) {
    companion object {
        private const val TAG = "WalletRepo"

        /** How long connecting to Spark may take before the start counts as failed. */
        val CONNECT_TIMEOUT = 30.seconds

        /** How long the wallet sync may take before the start counts as failed; a first sync took about 3.5 minutes. */
        val SYNC_TIMEOUT = 5.minutes

        /** How long the app may be off screen before Spark is disconnected, so a glance away keeps it connected. */
        val BACKGROUND_GRACE = 10.seconds

        /** How long an offline wallet waits before each try to sync again; the last wait repeats. */
        val OFFLINE_RETRY_DELAYS = listOf(5.seconds, 15.seconds, 60.seconds)
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val startMutex = Mutex()

    private val walletMutex = Mutex()

    private val isResumePending = AtomicBoolean(false)

    private var graceJob: Job? = null

    private val isForeground: Boolean get() = foregroundMonitor.isForeground.value

    private val _walletState = MutableStateFlow(WalletState())
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    val isSupported: Boolean get() = sparkService.isSupported

    init {
        scope.launch {
            sparkService.events.collect { refreshBalance() }
        }
        scope.launch {
            deviceLock.isLocked.collect { isLocked -> if (isLocked) lock() else scope.launch { resume() } }
        }
        scope.launch {
            networkMonitor.isOnline.collect { isOnline -> if (isOnline) scope.launch { resume() } else onNetworkLoss() }
        }
        scope.launch {
            foregroundMonitor.isForeground.collect { onForegroundChange(it) }
        }
        scope.launch { retryWhileOffline() }
    }

    suspend fun hasWallet(): Boolean = withContext(ioDispatcher) { seedStore.exists() }

    /** Whether the wallet was deleted because its seed key was lost, and the wearer was not told yet. */
    suspend fun isRemovedNoticePending(): Boolean = withContext(ioDispatcher) {
        runSuspendCatching { settingsStore.isRemovedNoticePending() }.getOrDefault(false)
    }

    /** Runs to the end even when the caller is cancelled, so the notice never shows twice. */
    suspend fun acknowledgeRemoval(): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        runSuspendCatching { settingsStore.setRemovedNoticePending(false) }
            .onFailure { Logger.warn("Failed to acknowledge wallet removal", it, context = TAG) }
    }

    /**
     * Run at every launch before the wallet is used: finishes a wipe that the last process did not complete, and
     * deletes Spark storage, a seed key and a half-written seed file that no stored seed owns.
     */
    suspend fun recover(): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        walletMutex.withLock { recoverLocked() }
    }.onFailure {
        Logger.error("Failed to recover wallet storage", it, context = TAG)
    }

    /**
     * Stores [seed] as this watch's wallet, after [recover]ing what an earlier wallet left. The caller validates the
     * recovery phrase first.
     */
    suspend fun restore(seed: WalletSeed): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        walletMutex.withLock {
            runSuspendCatching {
                if (!isSupported) throw SparkError.Unsupported()
                recoverLocked().getOrThrow()
                seedStore.save(seed).getOrThrow()
                _walletState.update { WalletState() }
            }
        }
    }.onFailure {
        Logger.error("Failed to restore wallet", it, context = TAG)
    }

    /**
     * Reads the lock state again and reconnects a wallet that a lock disconnected. The keyguard covers the app
     * without stopping it, so the activity calls this when its window regains focus.
     */
    suspend fun refreshLock() = withContext(ioDispatcher) {
        deviceLock.refresh()
        resume()
    }

    /**
     * Connects the stored wallet, syncs it and loads its balance; a wallet already connected only syncs it. Starts
     * run one at a time, and only while the app is on screen: in the background the start is put off as
     * [WalletStatus.Paused] until the app returns. It stays disconnected while the watch is locked, which only a
     * watch with a screen lock ever is. A wipe that did not complete is finished first, and nothing connects while
     * one runs. When the seed key is certainly lost, the wallet is wiped and [WalletStatus.Removed] reported. A sync
     * that does not reach Spark fails the start: [WalletStatus.Offline] once this session has synced,
     * [WalletStatus.Error] before. A start that a wipe, a lock or the background overtook connects nothing and leaves
     * the state to them. When the network, an unlock or the app came back while a start that then failed was
     * running, the start runs once more. Its caller cannot cancel it, so a start always ends in a settled state,
     * never left at [WalletStatus.Connecting]; its own timeouts bound it.
     */
    suspend fun start(): Result<Unit> = withContext(ioDispatcher + NonCancellable) { runStart() }

    private suspend fun runStart(): Result<Unit> {
        val result = startMutex.withLock { startLocked() }
        return if (result.isFailure && isResumePending.getAndSet(false) && canRetryStart()) runStart() else result
    }

    private suspend fun startLocked(): Result<Unit> {
        isResumePending.set(false)
        if (!isForeground) {
            _walletState.update { if (it.status in unpausedStatuses) it else it.copy(status = WalletStatus.Paused) }
            return Result.failure(WalletError.Backgrounded())
        }
        deviceLock.refresh()
        if (deviceLock.isLocked.value) {
            // A lock the system did not announce can find a wallet still connected: its last start failed, or its
            // last disconnect did.
            disconnectQuietly()
            markLocked()
            return Result.failure(WalletError.DeviceLocked())
        }
        finishPendingWipe().onFailure { error ->
            _walletState.update { it.copy(status = WalletStatus.Error) }
            return Result.failure(error)
        }
        val wipeToken = sparkService.wipeToken() ?: return Result.failure(SparkError.WipeInProgress())
        _walletState.update { it.copy(status = WalletStatus.Connecting) }
        return runSuspendCatching {
            val seed = seedStore.load().getOrThrow()
            val connectStart = TimeSource.Monotonic.markNow()
            withTimeoutOrNull(CONNECT_TIMEOUT) {
                sparkService.connect(seed, wipeToken)
                true
            } ?: throw WalletError.Timeout("connect")
            Logger.info("Connected wallet in '${connectStart.elapsedNow()}'", context = TAG)
            // A lock, a pause or a trip to the background that landed while connecting found nothing to disconnect.
            checkStillStarting()
            val syncStart = TimeSource.Monotonic.markNow()
            val sync = withTimeoutOrNull(SYNC_TIMEOUT) { sparkService.syncWallet() }
                ?: throw WalletError.Timeout("sync")
            if (!sync.hasReachedSpark) throw WalletError.NotSynced(sync.error)
            Logger.info("Synced wallet in '${syncStart.elapsedNow()}'", context = TAG)
            refreshBalance().getOrThrow()
            markReady(wipeToken)
        }.onFailure {
            if (!sparkService.isCurrent(wipeToken)) {
                Logger.info("Dropped wallet start that a wipe overtook", context = TAG)
                markOvertakenByWipe()
                return@onFailure
            }
            onStartFailure(it)
        }
    }

    private suspend fun markOvertakenByWipe() {
        val hasSeed = runSuspendCatching { seedStore.exists() }.getOrDefault(true)
        val status = if (hasSeed) WalletStatus.Error else WalletStatus.Missing
        _walletState.update { if (it.status == WalletStatus.Connecting) it.copy(status = status) else it }
    }

    private suspend fun finishPendingWipe(): Result<Unit> = runSuspendCatching {
        if (settingsStore.isWipePending()) recover().getOrThrow()
    }

    private fun checkStillStarting() {
        if (deviceLock.isLocked.value) throw WalletError.DeviceLocked()
        if (_walletState.value.status != WalletStatus.Connecting) throw WalletError.Overtaken()
        if (!isForeground) throw WalletError.Backgrounded()
    }

    private fun markReady(wipeToken: Int) {
        if (!sparkService.isCurrent(wipeToken)) throw SparkError.WipeInProgress()
        checkStillStarting()
        val previous = _walletState.getAndUpdate {
            if (it.status == WalletStatus.Connecting) it.copy(status = WalletStatus.Ready, hasSynced = true) else it
        }
        if (previous.status != WalletStatus.Connecting) throw WalletError.Overtaken()
    }

    private suspend fun onStartFailure(error: Throwable) {
        if (deviceLock.isLocked.value || error is SeedStoreError.DeviceLocked || error is WalletError.DeviceLocked) {
            Logger.info("Stopped wallet start because the watch locked", context = TAG)
            disconnectQuietly()
            markLocked()
            return
        }
        if (_walletState.value.status != WalletStatus.Connecting) {
            Logger.info("Stopped wallet start that a lock or the background overtook", context = TAG)
            disconnectQuietly()
            return
        }
        if (error is WalletError.Backgrounded) {
            Logger.info("Stopped wallet start because the app left the screen", context = TAG)
            disconnectQuietly()
            _walletState.update {
                if (it.status == WalletStatus.Connecting) it.copy(status = WalletStatus.Paused) else it
            }
            return
        }
        when (error) {
            is SeedStoreError.KeyInvalidated -> {
                Logger.error("Failed to start wallet", error, context = TAG)
                wipe(isKeyLost = true, isNoteRequired = false)
            }
            is SeedStoreError.NotFound -> {
                Logger.warn("Failed to start wallet", error, context = TAG)
                _walletState.update { it.copy(status = WalletStatus.Missing) }
            }
            else -> {
                Logger.error("Failed to start wallet", error, context = TAG)
                _walletState.update {
                    it.copy(status = if (it.hasSynced) WalletStatus.Offline else WalletStatus.Error)
                }
            }
        }
    }

    private fun canRetryStart(): Boolean {
        if (!isForeground) return false
        return when (_walletState.value.status) {
            WalletStatus.Paused -> true
            WalletStatus.Locked -> !deviceLock.isLocked.value
            WalletStatus.Offline, WalletStatus.Error -> networkMonitor.isOnline.value
            else -> false
        }
    }

    private fun onNetworkLoss() {
        _walletState.update { if (it.status == WalletStatus.Ready) it.copy(status = WalletStatus.Offline) else it }
    }

    private suspend fun lock() {
        // A disconnect that failed earlier left Spark connected under a disconnected status.
        val isDisconnected = disconnectQuietly()
        if (_walletState.value.status in disconnectedStatuses) return
        markLocked()
        if (isDisconnected) Logger.info("Disconnected wallet because the watch locked", context = TAG)
    }

    private fun markLocked() = _walletState.update {
        if (it.status in removedStatuses) it else it.copy(status = WalletStatus.Locked)
    }

    private fun onForegroundChange(isForeground: Boolean) {
        graceJob?.cancel()
        if (isForeground) {
            deviceLock.refresh()
            scope.launch { resume() }
            return
        }
        graceJob = scope.launch {
            delay(BACKGROUND_GRACE)
            // Returning to the app cancels only the wait above, never a pause that began.
            withContext(NonCancellable) { pause() }
        }
    }

    private suspend fun pause() {
        if (isForeground) return
        val isDisconnected = disconnectQuietly()
        _walletState.update { if (it.status in pausableStatuses) it.copy(status = WalletStatus.Paused) else it }
        if (isDisconnected) Logger.info("Disconnected wallet because the app left the screen", context = TAG)
        scope.launch { resume() }
    }

    private suspend fun resume() {
        if (!isForeground) return
        when (_walletState.value.status) {
            WalletStatus.Connecting -> {
                isResumePending.set(true)
                // A start that ended between the two reads of the status never saw the flag.
                if (_walletState.value.status != WalletStatus.Connecting && isResumePending.getAndSet(false)) resume()
            }
            WalletStatus.Locked, WalletStatus.Paused -> start()
            WalletStatus.Offline if networkMonitor.isOnline.value -> start()
            else -> Unit
        }
    }

    private suspend fun retryWhileOffline() {
        var attempt = 0
        combine(
            _walletState.map { it.status },
            foregroundMonitor.isForeground,
            networkMonitor.isOnline,
        ) { status, isForeground, isOnline -> status to (isForeground && isOnline) }
            .distinctUntilChanged()
            .collectLatest { (status, canSync) ->
                when (status) {
                    WalletStatus.Offline if canSync -> while (true) {
                        delay(OFFLINE_RETRY_DELAYS.getOrElse(attempt) { OFFLINE_RETRY_DELAYS.last() })
                        attempt++
                        scope.launch { resume() }
                    }
                    WalletStatus.Offline, WalletStatus.Connecting -> Unit
                    else -> attempt = 0
                }
            }
    }

    private suspend fun disconnectQuietly(): Boolean =
        runSuspendCatching { sparkService.disconnect() }
            .onFailure { Logger.warn("Failed to disconnect wallet", it, context = TAG) }
            .isSuccess

    /** Loads the balance from Spark. A balance read while the wallet is wiped is dropped. */
    suspend fun refreshBalance(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (sparkService.isWiping) return@runSuspendCatching
            val balance = sparkService.getInfo().balanceSats
            _walletState.update { if (sparkService.isWiping) it else it.copy(balanceSats = balance) }
        }
    }

    /**
     * Removes the wallet from the watch as one operation that the caller cannot cancel: it notes that a wipe is under
     * way, deletes the seed, then Spark's storage, then the settings together with the note, and ends in
     * [WalletStatus.Missing]. When the note cannot be written, even on a second try, nothing is deleted and the
     * failure is returned, so a removal the wearer asked for can always be finished by [recover]. Every step after
     * the note runs even when an earlier one fails, and the first failure is returned, with
     * [WalletStatus.Error]. The wallet counts as wiped once the seed file and Spark's storage are gone. When the
     * process dies or a step fails, the note stays and [recover] finishes the wipe at the next launch or the next
     * [start]. The wipe after a lost seed key ends in [WalletStatus.Removed] instead, and reports no
     * [WalletStatus.Missing] on the way, so the wearer is told at once.
     */
    suspend fun wipe(): Result<Unit> = wipe(isKeyLost = false, isNoteRequired = true)

    private suspend fun wipe(isKeyLost: Boolean, isNoteRequired: Boolean): Result<Unit> =
        withContext(ioDispatcher + NonCancellable) {
            walletMutex.withLock { wipeLocked(isKeyLost, isNoteRequired) }
        }.onFailure {
            Logger.error("Failed to wipe wallet", it, context = TAG)
        }

    private suspend fun wipeLocked(isKeyLost: Boolean, isNoteRequired: Boolean): Result<Unit> {
        val note = noteWipe(isKeyLost)
        if (isNoteRequired && note.isFailure) return note
        sparkService.beginWipe()
        try {
            val seed = seedStore.wipe()
            val spark = runSuspendCatching { sparkService.wipeStorage() }
            val isWiped = seed.isSuccess && spark.isSuccess
            val settings = if (isWiped) runSuspendCatching { settingsStore.finishWipe() } else Result.success(Unit)
            val status = when {
                isKeyLost -> WalletStatus.Removed
                isWiped -> WalletStatus.Missing
                else -> WalletStatus.Error
            }
            _walletState.update { WalletState(status = status) }
            return listOf(seed, spark, settings).firstOrNull { it.isFailure } ?: Result.success(Unit)
        } finally {
            sparkService.endWipe()
        }
    }

    private suspend fun noteWipe(isKeyLost: Boolean): Result<Unit> {
        val first = runSuspendCatching { settingsStore.beginWipe(isKeyLost) }
        if (first.isSuccess) return first
        Logger.warn("Failed to note the wallet wipe, trying again", first.exceptionOrNull(), context = TAG)
        return runSuspendCatching { settingsStore.beginWipe(isKeyLost) }
            .onFailure { Logger.warn("Failed to note the wallet wipe", it, context = TAG) }
    }

    private suspend fun recoverLocked(): Result<Unit> = runSuspendCatching {
        if (settingsStore.isWipePending()) {
            Logger.warn("Finishing a wallet wipe that did not complete", context = TAG)
            wipeLocked(isKeyLost = false, isNoteRequired = false).getOrThrow()
            return@runSuspendCatching
        }
        if (!seedStore.exists()) {
            seedStore.wipe().onFailure { Logger.warn("Failed to delete what a seed left behind", it, context = TAG) }
            sparkService.wipeStorage()
        }
    }
}

private val disconnectedStatuses =
    setOf(WalletStatus.Idle, WalletStatus.Locked, WalletStatus.Paused, WalletStatus.Removed, WalletStatus.Missing)

private val removedStatuses = setOf(WalletStatus.Removed, WalletStatus.Missing)

private val unpausedStatuses = setOf(WalletStatus.Ready, WalletStatus.Removed, WalletStatus.Missing)

private val pausableStatuses = setOf(WalletStatus.Connecting, WalletStatus.Ready, WalletStatus.Offline)

enum class WalletStatus {
    Idle,
    Connecting,
    Ready,

    /** This session synced, but the last sync did not reach Spark, so the balance shown is the last one known. */
    Offline,

    /** The wallet could not be loaded, and no sync reached Spark this session. */
    Error,

    /** Disconnected because the watch is locked. */
    Locked,

    /** Disconnected because the app left the screen; it starts again when the app returns. */
    Paused,

    /** The wallet was deleted from the watch because its seed key was lost. */
    Removed,

    /** No seed is stored, so there is no wallet to open: none was restored, or it was removed from the watch. */
    Missing,
}

sealed class WalletError(message: String) : AppError(message) {
    class DeviceLocked : WalletError("The watch is locked")

    /** The app is not on screen, where the wallet never starts. */
    class Backgrounded : WalletError("The app is in the background")

    /** A lock or the background disconnected the wallet while it started. */
    class Overtaken : WalletError("The wallet start was overtaken")

    /** The wallet sync did not reach Spark, as without a network. */
    class NotSynced(error: String?) : WalletError("Sync did not reach Spark: '$error'")

    /** Spark did not finish [step] in time. */
    class Timeout(step: String) : WalletError("Timed out on '$step'")
}

/** [balanceSats] is Spark's stored balance; only once [hasSynced] has this session reached Spark to confirm it. */
data class WalletState(
    val status: WalletStatus = WalletStatus.Idle,
    val balanceSats: ULong? = null,
    val hasSynced: Boolean = false,
)
