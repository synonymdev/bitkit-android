package to.bitkit.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.async.appScope
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.Toast
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.usecases.WipeIncomplete
import to.bitkit.usecases.WipeStep
import to.bitkit.usecases.WipeStepFailed
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs every wallet wipe from a durable intent in the keychain, so a wipe outlives the screen, the ViewModel and the
 * process that started it. The intent is written and read back before anything is deleted, it keeps the app locked
 * until the wipe completes, and an intent or PIN state that cannot be read blocks the app without deleting anything.
 */
@Singleton
class WipeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val walletRepo: Provider<WalletRepo>,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "WipeCoordinator"

        /** Waits between automatic retries; after the last one, a wipe retries on launch, foreground and Retry. */
        private val RETRY_DELAYS = listOf(5.seconds, 30.seconds, 2.minutes)

        /** Reasons whose completion is announced, as the user is not the one who started the wipe from a screen. */
        private val ANNOUNCED_REASONS = setOf(WipeReason.PIN_ATTEMPTS_EXCEEDED, WipeReason.RECOVERY_RESET)
    }

    private val scope = appScope(bgDispatcher, TAG)

    private val _state = MutableStateFlow<WipeState>(WipeState.Idle)
    val state: StateFlow<WipeState> = _state.asStateFlow()

    private val jobLock = Any()
    private var job: Job? = null
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var forcedWipeReason: WipeReason? = null

    /**
     * Raises the gate before anything can show the wallet or start the node. Only the fixed-key wipe intent is read
     * on the calling thread; the PIN attempts check, which opens the database, runs in the background behind the gate.
     */
    fun resumeAtLaunch() {
        if (!raiseGate(readIntent())) _state.update { WipeState.Checking }
        launchAttempts()
    }

    /**
     * Starts a wipe once its intent is durable. A forced wipe locks the app before anything else and keeps retrying
     * the intent write; any other reason returns the write failure, with nothing deleted and the app unchanged.
     */
    suspend fun begin(reason: WipeReason): Result<Unit> {
        val isForced = reason == WipeReason.PIN_ATTEMPTS_EXCEEDED
        if (isForced) {
            forcedWipeReason = reason
            _state.update { if (it.isGateUp) it else WipeState.Running }
        } else if (_state.value.isGateUp) {
            launchAttempts()
            return Result.success(Unit)
        }
        // Detached from the caller, so leaving the screen cannot strand an intent whose write already committed
        return scope.async { establishIntent(reason, isForced) }.await()
    }

    private suspend fun establishIntent(reason: WipeReason, isForced: Boolean): Result<Unit> {
        val result = if (readIntent() is WipeIntentState.Confirmed) Result.success(Unit) else writeIntent(reason)
        result.onFailure { error ->
            Logger.error("Failed to start wallet wipe for reason '$reason'", error, context = TAG)
            if (isForced) _state.update { WipeState.Failed(WipeStep.BEGIN, error) }
        }
        if (result.isSuccess || isForced) launchAttempts(isRetry = result.isFailure)
        return result
    }

    /**
     * Retries a failed wipe or re-reads an unreadable state right away, resumes an attempt that is no longer running,
     * and raises the gate for a pending or unreadable intent found while the app is unlocked.
     */
    fun retry() {
        when (_state.value) {
            is WipeState.Failed, is WipeState.Blocked -> retryRequests.trySend(Unit)
            WipeState.Running, WipeState.Checking -> Unit
            WipeState.Idle -> {
                scope.launch { if (raiseGate(readIntent())) launchAttempts() }
                return
            }
        }
        launchAttempts()
    }

    /**
     * Starts over from a gate that cannot be passed, by wiping the wallet as Forgot PIN would. Only a wipe can lower
     * the gate this way, so it keeps the app locked until the wipe completes.
     */
    fun reset() {
        if (_state.value !is WipeState.Blocked && _state.value !is WipeState.Failed) return
        Logger.warn("Resetting the wallet from the wipe gate", context = TAG)
        forcedWipeReason = forcedWipeReason ?: WipeReason.USER_RESET
        retry()
    }

    /** Locks the app behind the gate for a wallet state that could not be read, until a read succeeds. */
    fun block(error: Throwable) {
        _state.update { if (it == WipeState.Idle || it == WipeState.Checking) WipeState.Blocked(error) else it }
        launchAttempts(isRetry = true)
    }

    fun readIntent(): WipeIntentState = runCatching { keychain.loadWipePending() }.fold(
        onSuccess = { raw -> raw?.let { WipeIntentState.Confirmed(parseReason(it)) } ?: WipeIntentState.None },
        onFailure = { WipeIntentState.Unknown(it) },
    )

    private fun raiseGate(intent: WipeIntentState): Boolean {
        when (intent) {
            is WipeIntentState.Confirmed -> _state.update { WipeState.Running }
            is WipeIntentState.Unknown -> _state.update { WipeState.Blocked(intent.error) }
            WipeIntentState.None -> return false
        }
        return true
    }

    private fun launchAttempts(isRetry: Boolean = false) = synchronized(jobLock) {
        if (job?.isActive == true) return@synchronized
        job = scope.launch {
            retryRequests.tryReceive()
            if (!isRetry && attemptOrFail()) return@launch
            for (wait in RETRY_DELAYS) {
                withTimeoutOrNull(wait) { retryRequests.receive() }
                if (attemptOrFail()) return@launch
            }
        }
    }

    private suspend fun attemptOrFail(): Boolean = runCatching { attempt() }.getOrElse { error ->
        // Only this scope's own cancellation ends the attempts; any other error, a timeout too, is a failed attempt
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        Logger.error("Failed wallet wipe attempt", error, context = TAG)
        _state.update { if (it is WipeState.Running) WipeState.Failed(null, error) else WipeState.Blocked(error) }
        false
    }

    private suspend fun attempt(): Boolean = when (val intent = readIntent()) {
        is WipeIntentState.Confirmed -> wipe(intent.reason)

        // An intent that cannot be read may still be writable, and the wipe that follows clears the unreadable state
        is WipeIntentState.Unknown if forcedWipeReason != null -> forcedWipe()

        is WipeIntentState.Unknown -> {
            Logger.warn("Failed to read wipe intent", intent.error, context = TAG)
            _state.update { WipeState.Blocked(intent.error) }
            false
        }

        WipeIntentState.None if forcedWipeReason != null -> forcedWipe()

        // A launch that could not read the intent skipped the PIN attempts check, so it runs here instead
        WipeIntentState.None -> when (applyPinAttemptsState()) {
            PinAttemptsState.Exhausted -> forcedWipe()
            is PinAttemptsState.Unreadable -> false
            PinAttemptsState.NotExhausted -> lowerGate()
        }
    }

    private suspend fun forcedWipe(): Boolean {
        val reason = forcedWipeReason ?: WipeReason.PIN_ATTEMPTS_EXCEEDED
        _state.update { WipeState.Running }
        return writeIntent(reason).fold(
            onSuccess = { wipe(reason) },
            onFailure = { error ->
                Logger.error("Failed to write wipe intent", error, context = TAG)
                _state.update { WipeState.Failed(WipeStep.BEGIN, error) }
                false
            },
        )
    }

    private suspend fun lowerGate(): Boolean {
        // The launch check ran before anything read the wallet, so only a gate raised later refreshes the wallet state
        var wasChecking = false
        _state.update {
            wasChecking = it == WipeState.Checking
            if (wasChecking) WipeState.Idle else it
        }
        if (wasChecking) return true
        return runSuspendCatching { walletRepo.get().setWalletExistsState() }.fold(
            onSuccess = {
                _state.update { WipeState.Idle }
                true
            },
            onFailure = { error ->
                Logger.warn("Failed to read wallet state before lowering the wipe gate", error, context = TAG)
                _state.update { WipeState.Blocked(error) }
                false
            },
        )
    }

    private suspend fun wipe(reason: WipeReason?): Boolean {
        _state.update { WipeState.Running }
        Logger.info("Running wallet wipe for reason '$reason'", context = TAG)
        val error = walletRepo.get().wipeWallet().exceptionOrNull()
        if (error == null || error is WipeIncomplete) {
            complete(reason, isIncomplete = error != null)
            return true
        }
        val step = (error as? WipeStepFailed)?.step
        _state.update { WipeState.Failed(step, error) }
        step?.let { recordFailedStep(it) }
        return false
    }

    private suspend fun complete(reason: WipeReason?, isIncomplete: Boolean) {
        forcedWipeReason = null
        _state.update { WipeState.Idle }
        Logger.info("Completed wallet wipe for reason '$reason'", context = TAG)
        when {
            isIncomplete -> ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.security__wiped_title),
                description = context.getString(R.string.security__wipe_incomplete_text),
            )

            reason in ANNOUNCED_REASONS -> ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.security__wiped_title),
                description = context.getString(R.string.security__wiped_message),
            )
        }
    }

    private suspend fun writeIntent(reason: WipeReason): Result<Unit> = runSuspendCatching {
        val intent = WipeIntent(reason = reason, startedAt = clock.now().epochSeconds, lastStep = WipeStep.BEGIN.name)
        keychain.saveWipePending(json.encodeToString(intent))
        if (readIntent() !is WipeIntentState.Confirmed) throw WipeIntentNotConfirmed()
        Logger.info("Confirmed wipe intent for reason '$reason'", context = TAG)
    }

    private suspend fun recordFailedStep(step: WipeStep) {
        runSuspendCatching {
            val intent = keychain.loadWipePending()?.let { json.decodeFromString<WipeIntent>(it) }
                ?: return@runSuspendCatching
            keychain.saveWipePending(json.encodeToString(intent.copy(lastStep = step.name)))
        }.onFailure { Logger.warn("Failed to record wipe step '$step'", it, context = TAG) }
    }

    private fun applyPinAttemptsState(): PinAttemptsState = readPinAttemptsState().also { attempts ->
        when (attempts) {
            PinAttemptsState.NotExhausted -> Unit
            PinAttemptsState.Exhausted -> {
                Logger.warn("Promoting exhausted PIN attempts to a pending wipe", context = TAG)
                forcedWipeReason = WipeReason.PIN_ATTEMPTS_EXCEEDED
                _state.update { WipeState.Running }
            }

            is PinAttemptsState.Unreadable -> {
                Logger.warn("Failed to read PIN attempts", attempts.error, context = TAG)
                _state.update { WipeState.Blocked(attempts.error) }
            }
        }
    }

    private fun readPinAttemptsState(): PinAttemptsState = runCatching {
        keychain.exists(Keychain.Key.PIN.name) && keychain.loadPinAttemptsRemaining() <= 0
    }.fold(
        onSuccess = { isExhausted -> if (isExhausted) PinAttemptsState.Exhausted else PinAttemptsState.NotExhausted },
        onFailure = { PinAttemptsState.Unreadable(it) },
    )

    private fun parseReason(raw: String): WipeReason? =
        runCatching { json.decodeFromString<WipeIntent>(raw).reason }.getOrNull()

    private sealed interface PinAttemptsState {
        data object NotExhausted : PinAttemptsState
        data object Exhausted : PinAttemptsState
        data class Unreadable(val error: Throwable) : PinAttemptsState
    }
}

/** Reads the stored PIN attempt counter; a missing counter means no failed attempts. */
internal fun Keychain.loadPinAttemptsRemaining(): Int =
    loadString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name)?.toIntOrNull() ?: Env.PIN_ATTEMPTS

sealed interface WipeState {
    /** True while the root UI must show the wipe gate instead of the wallet, the PIN pad or biometrics. */
    val isGateUp: Boolean get() = this != Idle

    data object Idle : WipeState

    /** The launch check of the PIN attempts has not finished yet. */
    data object Checking : WipeState
    data object Running : WipeState
    data class Failed(val step: WipeStep?, val error: Throwable) : WipeState
    data class Blocked(val error: Throwable) : WipeState
}

sealed interface WipeIntentState {
    data object None : WipeIntentState
    data class Confirmed(val reason: WipeReason?) : WipeIntentState
    data class Unknown(val error: Throwable) : WipeIntentState
}

@Serializable
enum class WipeReason {
    @SerialName("pinAttemptsExceeded")
    PIN_ATTEMPTS_EXCEEDED,

    @SerialName("userReset")
    USER_RESET,

    @SerialName("forgotPin")
    FORGOT_PIN,

    @SerialName("recoveryReset")
    RECOVERY_RESET,

    @SerialName("dev")
    DEV,
}

@Serializable
private data class WipeIntent(
    @SerialName("v") val version: Int = 1,
    val reason: WipeReason,
    val startedAt: Long,
    val lastStep: String,
)

private class WipeIntentNotConfirmed : AppError("Wipe intent was not found after writing it")
