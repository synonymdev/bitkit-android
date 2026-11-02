package to.bitkit.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * The single check behind every PIN entry. It refuses before comparing once a wipe is pending or unverifiable or the
 * attempts are used up, records each attempt before comparing it, and starts the forced wipe on the last failure.
 */
@Singleton
class PinVerifier @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val wipeCoordinator: WipeCoordinator,
) {
    companion object {
        private const val TAG = "PinVerifier"

        /** How long attempts that could not be read count as used up before they are read again. */
        private val ATTEMPTS_RETRY_DELAY = 5.seconds
    }

    private val mutex = Mutex()

    private val storedAttempts: Flow<Int> = keychain.pinAttemptsRemaining()
        .map { it ?: Env.PIN_ATTEMPTS }
        .retryWhen { error, attempt ->
            if (attempt == 0L) Logger.error("Failed to read PIN attempts", error, context = TAG)
            emit(0)
            delay(ATTEMPTS_RETRY_DELAY)
            true
        }

    /** False while a wipe is pending or unverifiable, or once the PIN attempts are used up or cannot be read. */
    val canUseBiometrics: Flow<Boolean> = combine(wipeCoordinator.state, storedAttempts) { wipeState, attempts ->
        !wipeState.isGateUp && attempts > 0
    }.distinctUntilChanged()

    /** Runs to completion once started, so an attempt that was compared is always recorded. */
    suspend fun verify(pin: String): PinResult = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock { verifyLocked(pin) }
    }

    private suspend fun verifyLocked(pin: String): PinResult {
        if (wipeCoordinator.state.value.isGateUp) return PinResult.LockedOut
        when (wipeCoordinator.readIntent()) {
            WipeIntentState.None -> Unit
            is WipeIntentState.Confirmed, is WipeIntentState.Unknown -> {
                // Raises the gate, which explains why the PIN is refused
                wipeCoordinator.retry()
                return PinResult.LockedOut
            }
        }
        val hasPin = runSuspendCatching { keychain.exists(Keychain.Key.PIN.name) }
            .getOrElse { return unavailable("Failed to read PIN presence", it) }
        // Without a PIN there is nothing to check, and an entry must not count towards a wipe
        if (!hasPin) return unavailable("Found no PIN to check", AppError("No PIN set"))
        val attemptsRemaining = runSuspendCatching { keychain.loadPinAttemptsRemaining() }
            .getOrElse { return unavailable("Failed to read PIN attempts", it) }
        if (attemptsRemaining <= 0) return beginForcedWipe()

        // Recorded before comparing, so no guess is ever compared without counting
        runSuspendCatching { saveAttempts(attemptsRemaining - 1) }
            .getOrElse { return unavailable("Failed to record PIN attempt", it) }
        val storedPin = runSuspendCatching { keychain.loadString(Keychain.Key.PIN.name) }
            .getOrElse { return unavailable("Failed to read PIN", it) }
        return if (storedPin == pin) accept() else reject(attemptsRemaining - 1)
    }

    private suspend fun accept(): PinResult {
        runSuspendCatching { saveAttempts(Env.PIN_ATTEMPTS) }
            .onFailure { Logger.warn("Failed to reset PIN attempts", it, context = TAG) }
        return PinResult.Correct
    }

    private suspend fun reject(attemptsRemaining: Int): PinResult {
        if (attemptsRemaining > 0) return PinResult.Incorrect(attemptsRemaining)
        Logger.warn("Used up all PIN attempts, wiping wallet", context = TAG)
        return beginForcedWipe()
    }

    private suspend fun beginForcedWipe(): PinResult {
        wipeCoordinator.begin(WipeReason.PIN_ATTEMPTS_EXCEEDED)
        return PinResult.LockedOut
    }

    private suspend fun saveAttempts(attemptsRemaining: Int) =
        keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, attemptsRemaining.toString())

    private fun unavailable(message: String, error: Throwable): PinResult {
        Logger.error(message, error, context = TAG)
        return PinResult.Unavailable
    }
}

sealed interface PinResult {
    data object Correct : PinResult
    data class Incorrect(val attemptsRemaining: Int) : PinResult
    data object LockedOut : PinResult

    /** The PIN state could not be read or recorded; nothing is unlocked. */
    data object Unavailable : PinResult
}
