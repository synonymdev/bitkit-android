package to.bitkit.ui

import org.junit.Test
import kotlin.test.assertEquals

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
}
