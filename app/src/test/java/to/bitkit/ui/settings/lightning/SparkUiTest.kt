package to.bitkit.ui.settings.lightning

import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.repositories.SparkExitEarlierAddress
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    }
}
