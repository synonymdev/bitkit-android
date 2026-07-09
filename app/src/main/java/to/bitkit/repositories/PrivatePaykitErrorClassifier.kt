package to.bitkit.repositories

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

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
}
