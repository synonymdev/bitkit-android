package to.bitkit.ui.utils

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ScreenDeepLinksRegistrationTest : BaseUnitTest() {
    @Test
    fun `release never registers screen deeplinks`() {
        assertFalse(ScreenDeepLinks.isEnabled)
        assertTrue(ScreenDeepLinks.linksFor(Routes.Settings::class).isEmpty())
        assertTrue(ScreenDeepLinks.linksFor(Routes.Home::class).isEmpty())
        assertTrue(SheetDeepLinks.sheetIds.isEmpty())
        assertNull(SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/send")))
        assertNull(SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/settings")))
    }

    @Test
    fun `release still detaches screen uris from the intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bitkit://screen/settings"))

        val detached = ScreenDeepLinks.detachScreenUri(intent)

        assertTrue(detached)
        assertNull(intent.data)
    }
}
