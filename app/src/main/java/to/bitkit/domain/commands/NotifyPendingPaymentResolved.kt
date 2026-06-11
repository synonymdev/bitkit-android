package to.bitkit.domain.commands

import org.lightningdevkit.ldknode.Event
import to.bitkit.models.NotificationDetails

sealed interface NotifyPendingPaymentResolved {

    sealed interface Command : NotifyPendingPaymentResolved {
        val paymentHash: String

        data class Success(override val paymentHash: String) : Command
        data class Failure(override val paymentHash: String) : Command

        companion object {
            fun from(event: Event): Command? = when (event) {
                is Event.PaymentSuccessful -> Success(event.paymentHash)
                is Event.PaymentFailed -> event.paymentHash?.let { Failure(it) }
                else -> null
            }
        }
    }

    sealed interface Result : NotifyPendingPaymentResolved {
        data class ShowNotification(val notification: NotificationDetails) : Result
        data object Skip : Result
    }
}
