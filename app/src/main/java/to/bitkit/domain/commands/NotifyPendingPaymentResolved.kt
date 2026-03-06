package to.bitkit.domain.commands

import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import to.bitkit.models.NotificationDetails

sealed interface NotifyPendingPaymentResolved {

    sealed interface Command : NotifyPendingPaymentResolved {
        val paymentHash: String

        data class Success(override val paymentHash: String) : Command

        data class Failure(
            override val paymentHash: String,
            val reason: PaymentFailureReason?,
        ) : Command

        companion object {
            fun from(event: Event): Command? = when (event) {
                is Event.PaymentSuccessful -> event.paymentHash?.let { Success(it) }
                is Event.PaymentFailed -> event.paymentHash?.let { Failure(it, event.reason) }
                else -> null
            }
        }
    }

    sealed interface Result : NotifyPendingPaymentResolved {
        data class ShowNotification(val notification: NotificationDetails) : Result
        data object Skip : Result
    }
}
