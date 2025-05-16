package to.bitkit.ui.screens.wallets.activity.utils

import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType
import java.util.Calendar

val previewActivityItems = buildList {
    val today: Calendar = Calendar.getInstance()
    val yesterday: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
    val thisWeek: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -3) }
    val thisMonth: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -10) }
    val lastYear: Calendar = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }

    fun Calendar.epochSecond() = (timeInMillis / 1000).toULong()

    // Today
    add(
        Activity.Onchain(
            OnchainActivity(
                id = "1",
                txType = PaymentType.RECEIVED,
                txId = "01",
                value = 42_000_000_u,
                fee = 200_u,
                feeRate = 1_u,
                address = "bc1",
                confirmed = true,
                timestamp = today.epochSecond(),
                isBoosted = false,
                isTransfer = true,
                doesExist = true,
                confirmTimestamp = today.epochSecond(),
                channelId = "channelId",
                transferTxId = "transferTxId",
                createdAt = today.epochSecond(),
                updatedAt = today.epochSecond(),
            )
        )
    )

    // Yesterday
    add(
        Activity.Lightning(
            LightningActivity(
                id = "2",
                txType = PaymentType.SENT,
                status = PaymentState.PENDING,
                value = 30_000_u,
                fee = 15_u,
                invoice = "lnbc2",
                message = "Custom very long lightning activity message to test truncation",
                timestamp = yesterday.epochSecond(),
                preimage = "preimage1",
                createdAt = yesterday.epochSecond(),
                updatedAt = yesterday.epochSecond(),
            )
        )
    )

    // This Week
    add(
        Activity.Lightning(
            LightningActivity(
                id = "3",
                txType = PaymentType.RECEIVED,
                status = PaymentState.FAILED,
                value = 217_000_u,
                fee = 17_u,
                invoice = "lnbc3",
                message = "",
                timestamp = thisWeek.epochSecond(),
                preimage = "preimage2",
                createdAt = thisWeek.epochSecond(),
                updatedAt = thisWeek.epochSecond(),
            )
        )
    )

    // This Month
    add(
        Activity.Onchain(
            OnchainActivity(
                id = "4",
                txType = PaymentType.RECEIVED,
                txId = "04",
                value = 950_000_u,
                fee = 110_u,
                feeRate = 1_u,
                address = "bc1",
                confirmed = false,
                timestamp = thisMonth.epochSecond(),
                isBoosted = false,
                isTransfer = true,
                doesExist = true,
                confirmTimestamp = today.epochSecond() + 3600u,
                channelId = "channelId",
                transferTxId = "transferTxId",
                createdAt = thisMonth.epochSecond(),
                updatedAt = thisMonth.epochSecond(),
            )
        )
    )

    // Last Year
    add(
        Activity.Lightning(
            LightningActivity(
                id = "5",
                txType = PaymentType.SENT,
                status = PaymentState.SUCCEEDED,
                value = 200_000_u,
                fee = 1_u,
                invoice = "lnbc…",
                message = "",
                timestamp = lastYear.epochSecond(),
                preimage = null,
                createdAt = lastYear.epochSecond(),
                updatedAt = lastYear.epochSecond(),
            )
        )
    )
}
