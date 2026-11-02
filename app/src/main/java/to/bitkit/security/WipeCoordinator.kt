package to.bitkit.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
    private var isForcedWipeRequested = false

    @Volatile
    private var isProcessLockedOut = false

    /**
     * Raises the gate for a pending or unreadable intent, or for PIN attempts that were used up before the intent
     * existed or cannot be read, before anything can show the wallet or start the node.
     */
    fun resumeAtLaunch() {
        if (!raiseGate(readIntent()) && applyPinAttemptsState() == PinAttemptsState.NotExhausted) return
        launchAttempts()
    }

    /**
     * Starts a wipe once its intent is durable. A forced wipe locks the app before anything else and keeps retrying
     * the intent write; any other reason returns the write failure, with nothing deleted and the app unchanged.
     */
    suspend fun begin(reason: WipeReason): Result<Unit> {
        val isForced = reason == WipeReason.PIN_ATTEMPTS_EXCEEDED
        if (isForced) {
            isForcedWipeRequested = true
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

    /** Locks this process without starting a wipe, for a PIN attempt that could not be recorded. */
    fun lockOut(error: Throwable) {
        isProcessLockedOut = true
        _state.update { if (it.isGateUp) it else WipeState.Blocked(error) }
    }

    /**
     * Retries a failed wipe or re-reads an unreadable intent right away, and resumes a pending or unreadable intent
     * that has no attempt running.
     */
    fun retry() {
        when (_state.value) {
            is WipeState.Failed, is WipeState.Blocked -> retryRequests.trySend(Unit)
            WipeState.Running -> Unit
            WipeState.Idle -> if (!raiseGate(readIntent())) return
        }
        launchAttempts()
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
            if (!isRetry && attempt()) return@launch
            for (wait in RETRY_DELAYS) {
                withTimeoutOrNull(wait) { retryRequests.receive() }
                if (attempt()) return@launch
            }
        }
    }

    private suspend fun attempt(): Boolean = when (val intent = readIntent()) {
        is WipeIntentState.Confirmed -> wipe(intent.reason)
        is WipeIntentState.Unknown -> {
            Logger.warn("Failed to read wipe intent", intent.error, context = TAG)
            _state.update { WipeState.Blocked(intent.error) }
            false
        }

        WipeIntentState.None if isForcedWipeRequested -> forcedWipe()

        // A launch that could not read the intent skipped the PIN attempts check, so it runs here instead
        WipeIntentState.None -> when (applyPinAttemptsState()) {
            PinAttemptsState.Exhausted -> forcedWipe()
            is PinAttemptsState.Unreadable -> false
            PinAttemptsState.NotExhausted -> isProcessLockedOut || lowerGate()
        }
    }

    private suspend fun forcedWipe(): Boolean = writeIntent(WipeReason.PIN_ATTEMPTS_EXCEEDED).fold(
        onSuccess = { wipe(WipeReason.PIN_ATTEMPTS_EXCEEDED) },
        onFailure = { error ->
            Logger.error("Failed to write wipe intent", error, context = TAG)
            _state.update { WipeState.Failed(WipeStep.BEGIN, error) }
            false
        },
    )

    private suspend fun lowerGate(): Boolean = runSuspendCatching { walletRepo.get().setWalletExistsState() }.fold(
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

    private suspend fun wipe(reason: WipeReason?): Boolean {
        _state.update { WipeState.Running }
        Logger.info("Running wallet wipe for reason '$reason'", context = TAG)
        val error = walletRepo.get().wipeWallet().exceptionOrNull()
        if (error == null || error is WipeIncomplete) {
            complete(reason, warning = error)
            return true
        }
        val step = (error as? WipeStepFailed)?.step
        _state.update { WipeState.Failed(step, error) }
        step?.let { recordFailedStep(it) }
        return false
    }

    private suspend fun complete(reason: WipeReason?, warning: Throwable?) {
        isForcedWipeRequested = false
        isProcessLockedOut = false
        _state.update { WipeState.Idle }
        Logger.info("Completed wallet wipe for reason '$reason'", context = TAG)
        when {
            warning != null -> ToastEventBus.send(warning)
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
                isForcedWipeRequested = true
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
