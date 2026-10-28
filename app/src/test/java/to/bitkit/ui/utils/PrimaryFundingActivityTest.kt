package to.bitkit.ui.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import to.bitkit.R
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class PrimaryFundingActivityTest : BaseUnitTest() {
    private fun fundingActivity() = Activity.Onchain(
        OnchainActivity.create(
            id = "funding",
            txId = "11".repeat(32),
            txType = PaymentType.SENT,
            value = 48_000uL,
            fee = 529uL,
            address = "funding-address",
            timestamp = 1uL,
            isTransfer = true,
        ),
    )

    @Test
    fun `primary automatic funding shows only paid fee without changing stored transfer value`() {
        val activity = fundingActivity()

        assertEquals(529uL, activity.displayValue(isPrimaryFunding = true))
        assertEquals(R.string.primary_node__funding_fee, activity.getScreenTitleRes(isPrimaryFunding = true))
        assertEquals(48_000uL, activity.v1.value)
    }

    @Test
    fun `legacy transfers retain their amount and historical title`() {
        val activity = fundingActivity()

        assertEquals(48_529uL, activity.displayValue())
        assertEquals(R.string.wallet__activity_transfer_spending_done, activity.getScreenTitleRes())
    }

    @Test
    fun `received deposits never become primary funding fees`() {
        val activity = Activity.Onchain(fundingActivity().v1.copy(txType = PaymentType.RECEIVED, isTransfer = false))

        assertEquals(48_000uL, activity.displayValue(isPrimaryFunding = true))
        assertEquals(R.string.wallet__activity_bitcoin_received, activity.getScreenTitleRes(isPrimaryFunding = true))
    }
}
