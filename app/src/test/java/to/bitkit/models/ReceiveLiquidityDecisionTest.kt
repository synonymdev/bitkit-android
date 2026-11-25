package to.bitkit.models

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveLiquidityDecisionTest {

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
}
