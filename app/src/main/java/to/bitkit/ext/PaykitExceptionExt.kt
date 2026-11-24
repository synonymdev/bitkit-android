package to.bitkit.ext

import com.synonym.paykit.PaykitException

/** Protocol error code for an outbound Private Application Message larger than one pubky-noise message. */
private const val MESSAGE_TOO_LARGE_CODE = "message_too_large"

/** Identity error code for a signup code the homeserver refused during sign-up. */
private const val SIGNUP_CODE_REJECTED_CODE = "signup_code_rejected"

/** Storage error code Paykit uses for local and shared state failures. */
private const val STORAGE_ERROR_CODE = "storage_error"

/** Storage error contexts Paykit raises when the identity's shared state was saved by a newer Paykit. */
private val NEWER_STATE_CONTEXTS = listOf(
    "SDK state blob contains trailing bytes",
    "unsupported SDK state blob version",
)

/** Paykit exception messages are raw SDK details (`code=..., context=...`), so they must not reach a toast. */
fun Throwable.isPaykitError(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException }

fun Throwable.isPaykitIdentityError(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.Identity }

fun Throwable.isPaykitRecoveryRequired(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.RecoveryRequired }

fun Throwable.isPaykitTemporarilyUnavailable(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is PaykitException.ConcurrentUpdate || it is PaykitException.SharedStateBusy ||
            it is PaykitException.Transport
    }

fun Throwable.isPaykitMessageTooLarge(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.Protocol && it.code == MESSAGE_TOO_LARGE_CODE }

fun Throwable.isPaykitSignupCodeRejected(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.Identity && it.code == SIGNUP_CODE_REJECTED_CODE }

fun Throwable.isPaykitStateFromNewerVersion(): Boolean =
    generateSequence(this) { it.cause }.any { error ->
        error is PaykitException.Storage && error.code == STORAGE_ERROR_CODE &&
            NEWER_STATE_CONTEXTS.any { error.context.contains(it) }
    }
