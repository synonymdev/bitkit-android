package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveLiquidityDecisionTest {

    private val defaultAdditionalLiquidityParams = ReceiveAdditionalLiquidityParams(
        source = ReceiveLiquiditySource.SPENDING,
        invoiceAmountSats = 10_000u,
        inboundCapacitySats = 1_000u,
    )

    @Test
    fun `lightning invoice requires ready channel`() {
        assertFalse(
            ReceiveLiquidityDecision.canCreateLightningInvoice(
                hasReadyChannels = false,
                inboundCapacitySats = 1_000u,
                invoiceAmountSats = null,
            )
        )
    }

    @Test
    fun `variable lightning invoice requires non-zero inbound liquidity`() {
        assertFalse(
            ReceiveLiquidityDecision.canCreateLightningInvoice(
                hasReadyChannels = true,
                inboundCapacitySats = 0u,
                invoiceAmountSats = null,
            )
        )

        assertTrue(
            ReceiveLiquidityDecision.canCreateLightningInvoice(
                hasReadyChannels = true,
                inboundCapacitySats = 1u,
                invoiceAmountSats = null,
            )
        )
    }

    @Test
    fun `fixed lightning invoice requires inbound liquidity covering amount`() {
        assertTrue(
            ReceiveLiquidityDecision.canCreateLightningInvoice(
                hasReadyChannels = true,
                inboundCapacitySats = 5_000u,
                invoiceAmountSats = 5_000u,
            )
        )

        assertFalse(
            ReceiveLiquidityDecision.canCreateLightningInvoice(
                hasReadyChannels = true,
                inboundCapacitySats = 4_999u,
                invoiceAmountSats = 5_000u,
            )
        )
    }

    @Test
    fun `zero inbound needs no additional liquidity`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.None,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(inboundCapacitySats = 0u)
            )
        )
    }

    @Test
    fun `savings and auto edits need no additional liquidity`() {
        listOf(ReceiveLiquiditySource.SAVINGS, ReceiveLiquiditySource.AUTO).forEach {
            assertEquals(
                ReceiveAdditionalLiquidityAction.None,
                additionalLiquidityAction(
                    defaultAdditionalLiquidityParams.copy(source = it)
                )
            )
        }
    }

    @Test
    fun `spending above inbound routes to transfer unavailable`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.TransferUnavailable,
            additionalLiquidityAction(defaultAdditionalLiquidityParams)
        )
    }

    private fun additionalLiquidityAction(params: ReceiveAdditionalLiquidityParams) =
        ReceiveLiquidityDecision.additionalLiquidityAction(params)
}
