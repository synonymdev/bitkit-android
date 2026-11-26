package to.bitkit.ui.screens.profile

import app.cash.turbine.test
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class PayContactsViewModelTest : BaseUnitTest() {
    @Test
    fun `continue goes on to profile`() = test {
        val sut = PayContactsViewModel()

        sut.effects.test {
            sut.continueToProfile()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }
    }
}
