package to.bitkit.domain.commands

import org.lightningdevkit.ldknode.Event
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationState

sealed interface NotifyPaymentReceived {

    sealed interface Command : NotifyPaymentReceived {
        val sats: ULong
        val paymentId: String
        val includeNotification: Boolean

        data class Lightning(
            override val sats: ULong,
            override val paymentId: String,
            override val includeNotification: Boolean = false,
        ) : Command

        data class Onchain(
            override val sats: ULong,
            override val paymentId: String,
            override val includeNotification: Boolean = false,
        ) : Command

        companion object {
            fun from(event: Event, includeNotification: Boolean = false): Command? =
                when (event) {
                    is Event.PaymentReceived -> Lightning(
                        sats = event.amountMsat / 1000u,
                        paymentId = event.paymentHash,
                        includeNotification = includeNotification,
                    )

                    is Event.OnchainTransactionReceived -> {
                        val amountSats = event.details.amountSats
                        if (amountSats <= 0) null
                        else Onchain(
                            sats = amountSats.toULong(),
                            paymentId = event.txid,
                            includeNotification = includeNotification,
                        )
                    }

                    else -> null
                }
        }
    }

    sealed interface Result : NotifyPaymentReceived {
        data class ShowSheet(
            val details: NewTransactionSheetDetails,
        ) : Result

        data class ShowNotification(
            val details: NewTransactionSheetDetails,
            val notification: NotificationState,
        ) : Result

        data object Skip : Result
    }
}
