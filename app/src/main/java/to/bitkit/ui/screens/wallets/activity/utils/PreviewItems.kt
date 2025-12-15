package to.bitkit.ui.screens.wallets.activity.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import to.bitkit.ext.create
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
            OnchainActivity.create(
                id = "1",
                txType = PaymentType.RECEIVED,
                txId = "01",
                value = 42_000_u,
                fee = 200_u,
                address = "bc1",
                confirmed = true,
                timestamp = today.epochSecond(),
                isBoosted = true,
                boostTxIds = listOf("02", "03"),
                doesExist = false,
                confirmTimestamp = today.epochSecond(),
                channelId = "channelId",
                transferTxId = "transferTxId",
                createdAt = today.epochSecond() - 30_000u,
                updatedAt = today.epochSecond(),
            )
        )
    )

    // Yesterday
    add(
        Activity.Lightning(
            LightningActivity.create(
                id = "2",
                txType = PaymentType.SENT,
                status = PaymentState.PENDING,
                value = 30_000_u,
                invoice = "lnbc2",
                timestamp = yesterday.epochSecond(),
                fee = 15_u,
                message = "Custom very long lightning activity message to test truncation",
                preimage = "preimage1",
            )
        )
    )

    // This Week
    add(
        Activity.Lightning(
            LightningActivity.create(
                id = "3",
                txType = PaymentType.RECEIVED,
                status = PaymentState.FAILED,
                value = 217_000_u,
                invoice = "lnbc3",
                timestamp = thisWeek.epochSecond(),
                fee = 17_u,
                preimage = "preimage2",
            )
        )
    )

    // This Month
    add(
        Activity.Onchain(
            OnchainActivity.create(
                id = "4",
                txType = PaymentType.SENT,
                txId = "04",
                value = 950_000_u,
                fee = 110_u,
                address = "bc1",
                timestamp = thisMonth.epochSecond(),
                isTransfer = true,
                confirmTimestamp = today.epochSecond() + 3600u,
                channelId = "channelId",
                transferTxId = "transferTxId",
            )
        )
    )

    // Last Year
    add(
        Activity.Lightning(
            LightningActivity.create(
                id = "5",
                txType = PaymentType.SENT,
                status = PaymentState.SUCCEEDED,
                value = 200_000_u,
                invoice = "lnbc…",
                timestamp = lastYear.epochSecond(),
                fee = 1_u,
            )
        )
    )
}

fun previewOnchainActivityItems() = previewActivityItems.filter { it is Activity.Onchain }
fun previewLightningActivityItems() = previewActivityItems.filter { it is Activity.Lightning }
