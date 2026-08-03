package to.bitkit.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.components.Sheet
import to.bitkit.viewmodels.TransferEffect
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `a handled root screen link dismisses the open sheet`() {
        val result = shouldDismissSheetForScreenLink(handled = true, currentSheet = Sheet.Receive())

        assertTrue(result)
    }

    @Test
    fun `a rejected screen link leaves the open sheet alone`() {
        val result = shouldDismissSheetForScreenLink(handled = false, currentSheet = Sheet.Receive())

        assertFalse(result)
    }

    @Test
    fun `a handled root screen link with no sheet open dismisses nothing`() {
        val result = shouldDismissSheetForScreenLink(handled = true, currentSheet = null)

        assertFalse(result)
    }
}
