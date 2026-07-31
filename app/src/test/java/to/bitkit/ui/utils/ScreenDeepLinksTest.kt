package to.bitkit.ui.utils

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import to.bitkit.ui.sheets.SendRoute
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ScreenDeepLinksTest : BaseUnitTest() {
    private companion object {
        val SENSITIVE_ROUTES: List<KClass<out Routes>> = listOf(
            Routes.AuthCheck::class,
            Routes.CriticalUpdate::class,
            Routes.ExternalAmount::class,
            Routes.ExternalConfirm::class,
            Routes.ExternalSuccess::class,
            Routes.LegacyRnRecovery::class,
            Routes.LnurlChannel::class,
            Routes.RecoveryMnemonic::class,
            Routes.RecoveryMode::class,
            Routes.SavingsProgress::class,
            Routes.SettingUp::class,
            Routes.SpendingAdvanced::class,
            Routes.SpendingConfirm::class,
            Routes.SpendingHwSign::class,
            Routes.SpendingHwSigned::class,
        )
    }

    @Test
    fun `screen id is derived from the route name in kebab-case`() {
        val home = ScreenDeepLinks.screenId(Routes.Home::class)
        val activityDetail = ScreenDeepLinks.screenId(Routes.ActivityDetail::class)
        val rgsServer = ScreenDeepLinks.screenId(Routes.RgsServer::class)

        assertEquals("home", home)
        assertEquals("activity-detail", activityDetail)
        assertEquals("rgs-server", rgsServer)
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

    @Test
    fun `sensitive routes are denied`() {
        SENSITIVE_ROUTES.forEach { route ->
            assertTrue(ScreenDeepLinks.isDenied(route), "expected ${route.simpleName} to be denied")
            assertNull(ScreenDeepLinks.screenId(route))
            assertTrue(ScreenDeepLinks.linksFor(route).isEmpty())
        }
    }

    @Test
    fun `every route is either denied or reachable by a unique screen id`() {
        val ids = mutableMapOf<String, String>()

        Routes::class.sealedSubclasses.forEach { route ->
            val name = route.simpleName.orEmpty()
            val id = ScreenDeepLinks.screenId(route)

            if (ScreenDeepLinks.isDenied(route)) {
                assertNull(id, "denied route $name must not expose a screen id")
                return@forEach
            }

            assertNotNull(id, "route $name has no screen id")
            val clash = ids.put(id, name)
            assertNull(clash, "screen id '$id' is used by both $clash and $name")
            assertEquals(1, ScreenDeepLinks.linksFor(route).size, "route $name has no deep link")
        }
    }

    @Test
    fun `denied set contains only routes that still exist`() {
        val declared = Routes::class.sealedSubclasses.toSet()
        val denied = SENSITIVE_ROUTES.filter { it in declared }

        assertEquals(SENSITIVE_ROUTES.size, denied.size)
    }

    @Test
    fun `sheet routes are not exposed as screen deeplinks`() {
        val id = ScreenDeepLinks.screenId(SendRoute.Amount::class)
        val links = ScreenDeepLinks.linksFor(SendRoute.Amount::class)

        assertNull(id)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `screen deeplinks are recognised regardless of case`() {
        val lowercase = ScreenDeepLinks.isScreenDeepLink(Uri.parse("bitkit://screen/settings"))
        val uppercase = ScreenDeepLinks.isScreenDeepLink(Uri.parse("BITKIT://SCREEN/settings"))

        assertTrue(lowercase)
        assertTrue(uppercase)
    }

    @Test
    fun `other bitkit hosts are not screen deeplinks`() {
        val recoveryMode = ScreenDeepLinks.isScreenDeepLink(Uri.parse("bitkit://recovery-mode"))
        val pubkyAuth = ScreenDeepLinks.isScreenDeepLink(Uri.parse("bitkit://pubky-auth/callback"))
        val lightning = ScreenDeepLinks.isScreenDeepLink(Uri.parse("lightning:lnbc1"))

        assertFalse(recoveryMode)
        assertFalse(pubkyAuth)
        assertFalse(lightning)
    }
}
