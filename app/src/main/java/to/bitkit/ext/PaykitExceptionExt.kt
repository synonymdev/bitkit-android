package to.bitkit.ext

import com.synonym.paykit.PaykitException

/** Protocol error code for an outbound Private Application Message larger than one pubky-noise message. */
private const val MESSAGE_TOO_LARGE_CODE = "message_too_large"

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
