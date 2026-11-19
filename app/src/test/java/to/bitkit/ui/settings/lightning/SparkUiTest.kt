package to.bitkit.ui.settings.lightning

import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.repositories.SparkDeposit
import to.bitkit.repositories.SparkExitEarlierAddress
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkUiTest : BaseUnitTest() {
    @Test
    fun `emergency exit is offered for a kept balance while spark reads zero`() = test {
        val kept = SparkState(isReady = true, balanceSats = 0uL, keptBalanceSats = 10_000uL)

        assertTrue(assertNotNull(kept.toUi(mock())).showsExit)
    }

    @Test
    fun `emergency exit is not offered with nothing on spark`() {
        assertFalse(SparkUi(balanceSats = 0uL).showsExit)
    }

    @Test
    fun `earlier exit addresses keep spark listed once it is empty or offline`() = test {
        val earlier = SparkExitEarlierAddress(
            address = "bcrt1qearlier",
            path = "m/84'/1'/7797'/0/0",
            kind = SparkExitEarlierAddress.Kind.KEPT_CHANGE,
            sats = 294uL,
            atMillis = 0L,
        )

        val ui = assertNotNull(SparkState(isReady = false).toUi(mock(), earlierAddresses = listOf(earlier)))

        assertEquals(listOf(earlier), ui.earlierAddresses)
        assertFalse(ui.showsExit, "an empty spark offers no exit")
        assertFalse(ui.isReceiving, "an offline spark receives nothing")
    }

    @Test
    fun `a spark that read its state stays listed with its balance while it cannot connect`() = test {
        val offline = SparkState(isReady = false, hasReadState = true, balanceSats = 10_000uL)

        val ui = assertNotNull(offline.toUi(mock()))

        assertEquals(10_000uL, ui.balanceSats)
        assertFalse(ui.isReceiving, "an offline spark receives nothing")
        assertFalse(ui.isUsable, "an offline spark offered Transfer To Savings")
        assertTrue(assertNotNull(offline.copy(isReady = true).toUi(mock())).isUsable)
        val unread = SparkState(isReady = false, balanceSats = 10_000uL)
        assertNull(unread.toUi(mock()), "listed before Spark read anything")
    }

    @Test
    fun `a deposit shows the amount sent on-chain before and after spark claims it`() {
        val pending = SparkDepositUi(SparkDeposit("txid", 5_000uL, isClaimed = false))
        val claimed = SparkDepositUi(SparkDeposit("txid", 4_901uL, isClaimed = true, feeSats = 99uL))

        assertEquals(5_000uL, pending.grossSats)
        assertEquals(5_000uL, claimed.grossSats)
    }

    @Test
    fun `spark receives with no limit only while online, connected and not exiting`() = test {
        val ready = SparkState(isReady = true, balanceSats = 10_000uL)

        assertTrue(assertNotNull(ready.toUi(mock())).isReceiving)
        assertFalse(assertNotNull(ready.copy(isExiting = true).toUi(mock())).isReceiving)
        assertFalse(assertNotNull(ready.toUi(mock(), isOnline = false)).isReceiving)
    }
}
