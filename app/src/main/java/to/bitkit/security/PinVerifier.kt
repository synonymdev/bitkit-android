package to.bitkit.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single check behind every PIN entry. It refuses before comparing once a wipe is pending or unverifiable or the
 * attempts are used up, records each attempt before returning, and starts the forced wipe on the last failure.
 */
@Singleton
class PinVerifier @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val wipeCoordinator: WipeCoordinator,
) {
    companion object {
        private const val TAG = "PinVerifier"
    }

    private val mutex = Mutex()

    /** False while a wipe is pending or unverifiable, or once the PIN attempts are used up. */
    val canUseBiometrics: Flow<Boolean> = combine(
        wipeCoordinator.state,
        keychain.pinAttemptsRemaining().map { it ?: Env.PIN_ATTEMPTS }.catch { emit(0) },
    ) { wipeState, attemptsRemaining -> !wipeState.isGateUp && attemptsRemaining > 0 }
        .distinctUntilChanged()

    /** Runs to completion once started, so an attempt that was compared is always recorded. */
    suspend fun verify(pin: String): PinResult = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock { verifyLocked(pin) }
    }

    private suspend fun verifyLocked(pin: String): PinResult {
        if (wipeCoordinator.state.value.isGateUp || wipeCoordinator.readIntent() != WipeIntentState.None) {
            return PinResult.LockedOut
        }
        val attemptsRemaining = runSuspendCatching { keychain.loadPinAttemptsRemaining() }
            .getOrElse { return unavailable("Failed to read PIN attempts", it) }
        if (attemptsRemaining <= 0) return beginForcedWipe()

        val storedPin = runSuspendCatching { keychain.loadString(Keychain.Key.PIN.name) }
            .getOrElse { return unavailable("Failed to read PIN", it) }
        return if (storedPin == pin) accept(attemptsRemaining) else reject(attemptsRemaining - 1)
    }

    private suspend fun accept(attemptsRemaining: Int): PinResult {
        if (attemptsRemaining < Env.PIN_ATTEMPTS) resetAttempts()
        return PinResult.Correct
    }

    private suspend fun reject(attemptsRemaining: Int): PinResult {
        runSuspendCatching {
            keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, attemptsRemaining.toString())
        }.onFailure {
            wipeCoordinator.lockOut(it)
            return unavailable("Failed to record wrong PIN attempt", it)
        }
        if (attemptsRemaining > 0) return PinResult.Incorrect(attemptsRemaining)

        Logger.warn("Used up all PIN attempts, wiping wallet", context = TAG)
        return beginForcedWipe()
    }

    private suspend fun beginForcedWipe(): PinResult {
        wipeCoordinator.begin(WipeReason.PIN_ATTEMPTS_EXCEEDED)
        return PinResult.LockedOut
    }

    private suspend fun resetAttempts() {
        runSuspendCatching {
            keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, Env.PIN_ATTEMPTS.toString())
        }.onFailure { Logger.warn("Failed to reset PIN attempts", it, context = TAG) }
    }

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
