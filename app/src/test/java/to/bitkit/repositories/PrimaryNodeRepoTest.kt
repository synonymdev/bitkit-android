package to.bitkit.repositories

import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import to.bitkit.utils.LdkError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrimaryNodeRepoTest {

    @Test
    fun `funding plan moves confirmed savings minus the mining fee`() {
        val plan = primaryFundingPlan(spendableSats = 50_000uL, inputs = 1uL, feeRate = 2uL, hasChannel = true)

        requireNotNull(plan)
        assertEquals(1_200uL, plan.feeSats)
        assertEquals(48_800uL, plan.amountSats)
        assertFalse(plan.opensChannel)
        assertTrue(plan.isAutomatic)
    }

    @Test
    fun `funding opens a channel when the wallet has none`() {
        val plan = primaryFundingPlan(spendableSats = 50_000uL, inputs = 1uL, feeRate = 1uL, hasChannel = false)

        assertTrue(requireNotNull(plan).opensChannel)
    }

    @Test
    fun `automatic funding waits below the minimum or above the fee limit`() {
        val small = primaryFundingPlan(spendableSats = 24_999uL, inputs = 1uL, feeRate = 1uL, hasChannel = true)
        val expensive = primaryFundingPlan(spendableSats = 30_000uL, inputs = 1uL, feeRate = 3uL, hasChannel = true)

        assertFalse(requireNotNull(small).isAutomatic)
        assertFalse(requireNotNull(expensive).isAutomatic)
    }

    @Test
    fun `funding plan is empty when nothing can be moved`() {
        assertNull(primaryFundingPlan(spendableSats = 0uL, inputs = 0uL, feeRate = 1uL, hasChannel = true))
        assertNull(primaryFundingPlan(spendableSats = 500uL, inputs = 1uL, feeRate = 1uL, hasChannel = true))
    }

    @Test
    fun `liquidity errors map to what the user is told`() {
        fun map(e: NodeException) = LdkError(e).toPrimaryReceiveError()

        assertIs<PrimaryReceiveError.InsufficientFunds>(map(NodeException.LiquiditySourceInsufficientFunds("")))
        assertIs<PrimaryReceiveError.Rejected>(map(NodeException.LiquidityRequestRejected("")))
        assertIs<PrimaryReceiveError.TimedOut>(map(NodeException.LiquidityRequestTimedOut("")))
        assertIs<PrimaryReceiveError.AmountTooSmall>(map(NodeException.LiquidityReceiveAmountTooSmall("")))
    }

    @Test
    fun `an unreachable primary is reported as offline`() {
        val error = LdkError(NodeException.ConnectionFailed("")).toPrimaryReceiveError()

        assertIs<PrimaryReceiveError.Offline>(error)
    }
}
