package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class PaykitOnchainPaymentProofLookupTest : BaseUnitTest() {
    private val activityRepo = mock<ActivityRepo>()
    private val lookup = PaykitOnchainPaymentProofLookup(activityRepo)

    @Test
    fun `payment lookup uses one scoped activity query`() = test {
        val walletId = "hardware-wallet"
        val activity = Activity.Onchain(
            OnchainActivity.create(
                walletId = walletId,
                id = "activity-id",
                txType = PaymentType.SENT,
                txId = "transaction-id",
                value = 1_000uL,
                fee = 100uL,
                address = "bcrt1qpaymentproof",
                timestamp = 1uL,
            )
        )
        whenever(
            activityRepo.getActivities(
                walletId = walletId,
                filter = ActivityFilter.ONCHAIN,
                txType = PaymentType.SENT,
            )
        ).thenReturn(Result.success(listOf(activity)))

        val transactionIds = lookup.existingTransactionIds(
            address = activity.v1.address,
            amountSats = activity.v1.value,
            walletId = walletId,
        )

        assertEquals(setOf(activity.v1.txId), transactionIds)
        verify(activityRepo).getActivities(
            walletId = walletId,
            filter = ActivityFilter.ONCHAIN,
            txType = PaymentType.SENT,
        )
    }
}
