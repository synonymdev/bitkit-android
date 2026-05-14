package to.bitkit.repositories

import com.synonym.paykit.PaykitFfiException
import org.lightningdevkit.ldknode.NodeException

internal object PrivatePaykitErrorClassifier {
    fun isDuplicatePaymentError(error: Throwable): Boolean {
        val errors = error.causes()
        if (errors.any { it is NodeException.DuplicatePayment }) return true

        val reason = errors.mapNotNull { it.message }
            .joinToString(separator = " ")
            .lowercase()
        return "duplicate payment" in reason || "duplicatepayment" in reason
    }

    fun shouldCountAsStaleLinkFailure(error: Throwable): Boolean {
        val errors = error.causes()
        if (errors.any { it is PaykitFfiException.Session }) return false

        return errors.flatMap { it.staleLinkFailureReasons() }
            .any { isNoiseStateFailure(it) || isEncryptedLinkStateFailure(it) }
    }

    fun shouldRetryLinkEstablishmentFailure(error: Throwable): Boolean =
        error.causes().none {
            it is PrivatePaykitError.PrivateUnavailable || it is PrivatePaykitError.StaleLinkState
        }

    fun isEncryptedHandshakeStateFailure(error: Throwable): Boolean {
        val reason = error.message.orEmpty().lowercase()
        return isNoiseStateFailure(reason) ||
            isEncryptedLinkStateFailure(reason) ||
            listOf("restoreplayerror", "handshake restore failed").any { it in reason }
    }

    fun isEncryptedHandshakePendingError(error: Throwable): Boolean {
        val reason = error.message.orEmpty().lowercase()
        return "transition_transport failed" in reason && "ishandshake" in reason
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun Throwable.staleLinkFailureReasons(): List<String> = when (this) {
        is PaykitFfiException.Transport -> listOf(reason)
        is PaykitFfiException.InvalidData -> listOf(reason)
        is PaykitFfiException.NotFound -> listOf(reason)
        is PaykitFfiException.Validation -> listOf(reason)
        is PaykitFfiException.Session -> emptyList()
        else -> listOfNotNull(message)
    }

    private fun isNoiseStateFailure(reason: String): Boolean {
        val lowercasedReason = reason.lowercase()
        return listOf("decrypt", "decryption", "cipher", "noise state", "counter", "invalid tag", "bad mac")
            .any { it in lowercasedReason }
    }

    private fun isEncryptedLinkStateFailure(reason: String): Boolean {
        val lowercasedReason = reason.lowercase()
        return listOf(
            "unknown encrypted-link handle",
            "unknown encrypted link handle",
            "encrypted-link handle is closed",
            "encrypted link handle is closed",
            "failed to restore encrypted link",
            "encrypted link restore requires transport-phase snapshot",
            "remote_pubkey does not match snapshot recipient",
        ).any { it in lowercasedReason }
    }
}
