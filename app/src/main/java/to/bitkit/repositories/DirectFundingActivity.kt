package to.bitkit.repositories

import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.lightningdevkit.ldknode.DirectFundingPaymentDetails
import org.lightningdevkit.ldknode.DirectFundingPaymentStatus
import to.bitkit.ext.create
import to.bitkit.models.DIRECT_FUNDING_ACTIVITY_PREFIX

internal fun directFundingActivity(
    payment: DirectFundingPaymentDetails,
    previous: OnchainActivity?,
    confirmedAt: ULong,
): OnchainActivity {
    val timestamp = payment.createdAt / 1000uL
    val confirmed = payment.status == DirectFundingPaymentStatus.CONFIRMED
    return OnchainActivity.create(
        id = DIRECT_FUNDING_ACTIVITY_PREFIX + payment.requestId,
        txType = if (payment.isOutbound) PaymentType.SENT else PaymentType.RECEIVED,
        txId = payment.fundingTxid.orEmpty(),
        value = payment.amountSats,
        fee = if (payment.isOutbound) payment.feeSats ?: 0uL else 0uL,
        address = DIRECT_FUNDING_ACTIVITY_PREFIX + payment.requestId,
        timestamp = timestamp,
        confirmed = confirmed,
        doesExist = payment.status != DirectFundingPaymentStatus.FAILED &&
            payment.status != DirectFundingPaymentStatus.ABORTED,
        confirmTimestamp = if (confirmed) previous?.confirmTimestamp ?: confirmedAt else null,
        contact = previous?.contact,
        createdAt = previous?.createdAt ?: timestamp,
        updatedAt = timestamp,
        seenAt = previous?.seenAt,
    )
}
