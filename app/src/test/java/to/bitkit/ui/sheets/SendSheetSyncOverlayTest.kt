package to.bitkit.ui.sheets

import org.junit.Test
import to.bitkit.repositories.SparkState
import to.bitkit.viewmodels.QuickPayData
import to.bitkit.viewmodels.SendMethod
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendSheetSyncOverlayTest {
    private val funded = SparkState(isSupported = true, isReady = true, balanceSats = 100_000uL)

    @Test
    fun `a scanned invoice a ready funded spark can pay skips the wait for a stopped node`() {
        val sparkCanPay = sparkCanPayInvoice(SendMethod.LIGHTNING, 1_000uL, funded)

        assertTrue(sparkCanPay)
        assertFalse(waitsForNode(false, hasAnyChannels = true, hasUsableChannels = false, sparkCanPay = sparkCanPay))
        assertFalse(waitsForNode(true, hasAnyChannels = true, hasUsableChannels = false, sparkCanPay = sparkCanPay))
    }

    @Test
    fun `confirm and quick pay go ahead without the node when spark can pay`() {
        assertTrue(canSendWithoutWaiting(isNodeRunning = false, isHardwareWallet = false, sparkCanPay = true))
        assertFalse(canSendWithoutWaiting(isNodeRunning = false, isHardwareWallet = false, sparkCanPay = false))
        assertTrue(sparkCanPayQuickPay(QuickPayData.Bolt11(1_000uL, "lnbc1"), funded))
        assertFalse(sparkCanPayQuickPay(QuickPayData.Bolt11(1_000uL, "lnbc1"), funded.copy(isReady = false)))
    }

    @Test
    fun `on-chain, amountless and payments spark can't cover still wait for a stopped node`() {
        val cases = listOf(
            sparkCanPayInvoice(SendMethod.ONCHAIN, 1_000uL, funded),
            sparkCanPayInvoice(SendMethod.LIGHTNING, 0uL, funded),
            sparkCanPayInvoice(SendMethod.LIGHTNING, null, funded),
            sparkCanPayInvoice(SendMethod.LIGHTNING, 1_000uL, funded.copy(isReady = false)),
            sparkCanPayInvoice(SendMethod.LIGHTNING, 1_000uL, funded.copy(balanceSats = 1_000uL)),
        )

        cases.forEach { sparkCanPay ->
            assertFalse(sparkCanPay)
            assertTrue(waitsForNode(false, hasAnyChannels = false, hasUsableChannels = false, sparkCanPay))
        }
        assertFalse(waitsForNode(true, hasAnyChannels = false, hasUsableChannels = false, sparkCanPay = false))
        assertTrue(waitsForNode(true, hasAnyChannels = true, hasUsableChannels = false, sparkCanPay = false))
        assertFalse(waitsForNode(true, hasAnyChannels = true, hasUsableChannels = true, sparkCanPay = false))
    }
}
