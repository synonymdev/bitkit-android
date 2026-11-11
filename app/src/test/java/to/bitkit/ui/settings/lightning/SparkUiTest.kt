package to.bitkit.ui.settings.lightning

import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
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
}
