package to.bitkit.ui.screens.wallets.activity.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.ext.create
import to.bitkit.models.ActivityWalletType
import java.util.Calendar

val previewActivityItems: ImmutableList<Activity> = buildList {
    val today: Calendar = Calendar.getInstance()
    val yesterday: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
    val thisWeek: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -3) }
    val thisMonth: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -10) }
    val lastYear: Calendar = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }

    fun Calendar.epochSecond() = (timeInMillis / 1000).toULong()

    add(
        onchainPreviewItem(
            id = "1",
            txType = PaymentType.RECEIVED,
            value = 42_000uL,
            fee = 200uL,
            timestamp = today.epochSecond(),
            txId = "01",
            confirmed = true,
            isBoosted = true,
            doesExist = false,
        )
    )

    add(
        lightningPreviewItem(
            id = "2",
            txType = PaymentType.SENT,
            status = PaymentState.PENDING,
            value = 30_000uL,
            fee = 15uL,
            timestamp = yesterday.epochSecond(),
            message = "Custom very long lightning activity message to test truncation",
        )
    )

    add(
        lightningPreviewItem(
            id = "3",
            txType = PaymentType.RECEIVED,
            status = PaymentState.FAILED,
            value = 217_000uL,
            fee = 17uL,
            timestamp = thisWeek.epochSecond(),
        )
    )

    add(
        onchainPreviewItem(
            id = "4",
            txType = PaymentType.SENT,
            value = 950_000uL,
            fee = 110uL,
            timestamp = thisMonth.epochSecond(),
            txId = "04",
            isTransfer = true,
        )
    )

    add(
        lightningPreviewItem(
            id = "5",
            txType = PaymentType.SENT,
            status = PaymentState.SUCCEEDED,
            value = 200_000uL,
            fee = 1uL,
            timestamp = lastYear.epochSecond(),
        )
    )
}.toImmutableList()

fun previewOnchainActivityItems() = previewActivityItems.filterIsInstance<Activity.Onchain>().toImmutableList()

fun previewLightningActivityItems() = previewActivityItems.filterIsInstance<Activity.Lightning>().toImmutableList()

@Suppress("LongParameterList")
private fun lightningPreviewItem(
    id: String,
    txType: PaymentType,
    status: PaymentState,
    value: ULong,
    fee: ULong,
    timestamp: ULong,
    message: String = "",
) = Activity.Lightning(
    LightningActivity.create(
        id = id,
        walletId = ActivityWalletType.BITKIT.id(),
        txType = txType,
        status = status,
        value = value,
        fee = fee,
        invoice = "lnbc$id",
        message = message,
        timestamp = timestamp,
    )
)

@Suppress("LongParameterList")
private fun onchainPreviewItem(
    id: String,
    txType: PaymentType,
    value: ULong,
    fee: ULong,
    timestamp: ULong,
    txId: String,
    confirmed: Boolean = false,
    isBoosted: Boolean = false,
    isTransfer: Boolean = false,
    doesExist: Boolean = true,
) = Activity.Onchain(
    OnchainActivity.create(
        id = id,
        walletId = ActivityWalletType.BITKIT.id(),
        txType = txType,
        value = value,
        fee = fee,
        txId = txId,
        address = "bc1qpreview$id",
        timestamp = timestamp,
        confirmed = confirmed,
        isBoosted = isBoosted,
        isTransfer = isTransfer,
        doesExist = doesExist,
    )
)
