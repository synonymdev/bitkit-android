package to.bitkit.ui

import org.junit.Test
import to.bitkit.viewmodels.TransferEffect
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentViewTest {
    @Test
    fun `spending start route uses intro until seen`() {
        assertEquals(Routes.SpendingIntro, transferSpendingStartRoute(hasSeenSpendingIntro = false))
        assertEquals(Routes.SpendingAmount, transferSpendingStartRoute(hasSeenSpendingIntro = true))
    }

    @Test
    fun `hardware spending start route keeps device id after intro`() {
        val deviceId = "trezor-1"

        assertEquals(Routes.SpendingIntroHw(deviceId), transferSpendingStartRoute(false, deviceId))
        assertEquals(Routes.SpendingAmountHw(deviceId), transferSpendingStartRoute(true, deviceId))
    }

    @Test
    fun `transfer effect destinations cover funding paid and hw signed`() {
        assertEquals(Routes.SettingUp, transferEffectDestination(TransferEffect.OnSpendingFundingPaid))
        assertEquals(Routes.SpendingHwSigned, transferEffectDestination(TransferEffect.OnHwTxSigned))
        assertNull(transferEffectDestination(TransferEffect.OnOrderCreated))
    }
}
