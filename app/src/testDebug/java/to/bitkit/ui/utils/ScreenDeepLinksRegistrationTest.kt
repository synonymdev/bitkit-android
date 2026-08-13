package to.bitkit.ui.utils

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ScreenDeepLinksRegistrationTest : BaseUnitTest() {
    @Test
    fun `debug registers screen deeplinks`() {
        assertTrue(ScreenDeepLinks.isEnabled)
        assertTrue(ScreenDeepLinks.shouldQueue(true))
    }
}
