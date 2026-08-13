package to.bitkit.ui.utils

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ScreenDeepLinksRegistrationTest : BaseUnitTest() {
    @Test
    fun `debug runtime registers screen deeplinks`() {
        assertTrue(ScreenDeepLinkRuntime.isEnabled)
    }

    @Test
    fun `routes without arguments produce a bare pattern`() {
        val links = ScreenDeepLinks.linksFor(Routes.Settings::class)

        assertEquals(1, links.size)
        assertEquals("bitkit://screen/settings", links.single().uriPattern)
    }

    @Test
    fun `required arguments are appended as path segments`() {
        val links = ScreenDeepLinks.linksFor(Routes.ActivityAssignContact::class)

        assertEquals("bitkit://screen/activity-assign-contact/{id}", links.single().uriPattern)
    }

    @Test
    fun `a route with both argument kinds keeps the required one in the path`() {
        val links = ScreenDeepLinks.linksFor(Routes.ActivityDetail::class)

        assertEquals("bitkit://screen/activity-detail/{id}?walletId={walletId}", links.single().uriPattern)
    }

    @Test
    fun `arguments with defaults are appended as query parameters`() {
        val links = ScreenDeepLinks.linksFor(Routes.Contacts::class)

        assertEquals(
            "bitkit://screen/contacts?showAddContactSheet={showAddContactSheet}",
            links.single().uriPattern,
        )
    }
}
