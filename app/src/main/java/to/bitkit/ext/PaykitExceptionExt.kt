package to.bitkit.ext

import com.synonym.paykit.PaykitException

fun Throwable.isPaykitIdentityError(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.Identity }

fun Throwable.isPaykitRecoveryRequired(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitException.RecoveryRequired }
