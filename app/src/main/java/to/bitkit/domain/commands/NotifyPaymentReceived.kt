package to.bitkit.domain.commands

import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationDetails

sealed interface NotifyPaymentReceived {

    sealed interface Command : NotifyPaymentReceived {
        val includeNotification: Boolean

        data class Lightning(
            val event: Event.PaymentReceived,
            override val includeNotification: Boolean = false,
        ) : Command

        /**
         * An incoming onchain transaction. [confirmationTime] is the block timestamp in seconds since the
         * UNIX epoch, set when the wallet first saw the transaction already confirmed without a prior
         * mempool event.
         */
        data class Onchain(
            val txid: String,
            val details: TransactionDetails,
            val confirmationTime: ULong? = null,
            override val includeNotification: Boolean = false,
        ) : Command {
            val isConfirmedOnly: Boolean get() = confirmationTime != null
        }

        companion object {
            fun from(event: Event, includeNotification: Boolean = false): Command? =
                when (event) {
                    is Event.PaymentReceived -> Lightning(
                        event = event,
                        includeNotification = includeNotification,
                    )

                    is Event.OnchainTransactionReceived -> Onchain(
                        txid = event.txid,
                        details = event.details,
                        includeNotification = includeNotification,
                    )

                    is Event.OnchainTransactionConfirmed -> Onchain(
                        txid = event.txid,
                        details = event.details,
                        confirmationTime = event.confirmationTime,
                        includeNotification = includeNotification,
                    )

                    else -> null
                }
        }
    }

    sealed interface Result : NotifyPaymentReceived {
        data class ShowSheet(
            val sheet: NewTransactionSheetDetails,
        ) : Result

        data class ShowNotification(
            val sheet: NewTransactionSheetDetails,
            val notification: NotificationDetails,
        ) : Result

        data object Skip : Result
    }
}
