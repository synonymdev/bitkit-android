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
        minCjitSats = 5_000u,
        maxCjitAmountSats = 100_000u,
        isGeoBlocked = false,
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
    fun `zero inbound does not route to additional CJIT`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.None,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(inboundCapacitySats = 0u)
            )
        )
    }

    @Test
    fun `savings and auto edits do not route to CJIT`() {
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
    fun `below CJIT minimum routes to amount picker`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.ChooseAmount,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(invoiceAmountSats = 4_000u)
            )
        )
    }

    @Test
    fun `at CJIT minimum creates CJIT`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.CreateCjit(5_000u),
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(invoiceAmountSats = 5_000u)
            )
        )
    }

    @Test
    fun `over max CJIT amount routes to amount picker`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.ChooseAmount,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(invoiceAmountSats = 100_001u)
            )
        )
    }

    @Test
    fun `unknown max CJIT amount routes to amount picker`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.ChooseAmount,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(maxCjitAmountSats = null)
            )
        )
    }

    @Test
    fun `geo-blocked routes to geo-block screen`() {
        assertEquals(
            ReceiveAdditionalLiquidityAction.GeoBlocked,
            additionalLiquidityAction(
                defaultAdditionalLiquidityParams.copy(isGeoBlocked = true)
            )
        )
    }

    @Test
    fun `CJIT limits are fetched only when additional liquidity can use them`() {
        assertFalse(
            ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = ReceiveLiquiditySource.AUTO,
                invoiceAmountSats = 10_000u,
                inboundCapacitySats = 1_000u,
                isGeoBlocked = false,
            )
        )

        assertFalse(
            ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = ReceiveLiquiditySource.SPENDING,
                invoiceAmountSats = 10_000u,
                inboundCapacitySats = 0u,
                isGeoBlocked = false,
            )
        )

        assertFalse(
            ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = ReceiveLiquiditySource.SPENDING,
                invoiceAmountSats = 10_000u,
                inboundCapacitySats = 1_000u,
                isGeoBlocked = true,
            )
        )

        assertTrue(
            ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = ReceiveLiquiditySource.SPENDING,
                invoiceAmountSats = 10_000u,
                inboundCapacitySats = 1_000u,
                isGeoBlocked = false,
            )
        )
    }

    private fun additionalLiquidityAction(params: ReceiveAdditionalLiquidityParams) =
        ReceiveLiquidityDecision.additionalLiquidityAction(params)
}
