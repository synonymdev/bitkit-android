package to.bitkit.ui.utils

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ScreenDeepLinksTest : BaseUnitTest() {
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
        if (!ScreenDeepLinks.isEnabled) return
        val links = ScreenDeepLinks.linksFor(Routes.Settings::class)

        assertEquals(1, links.size)
        assertEquals("bitkit://screen/settings", links.single().uriPattern)
    }

    @Test
    fun `required arguments are appended as path segments`() {
        if (!ScreenDeepLinks.isEnabled) return
        val links = ScreenDeepLinks.linksFor(Routes.ActivityAssignContact::class)

        assertEquals("bitkit://screen/activity-assign-contact/{id}", links.single().uriPattern)
    }

    @Test
    fun `SpendingHwSign required arguments are wallet and amount path segments`() {
        if (!ScreenDeepLinks.isEnabled) return
        val links = ScreenDeepLinks.linksFor(Routes.SpendingHwSign::class)

        assertEquals(
            "bitkit://screen/spending-hw-sign/{walletId}/{amountSats}",
            links.single().uriPattern,
        )
    }

    @Test
    fun `spendingHwSignLink reads the wallet id and amount from the path`() {
        if (!ScreenDeepLinks.isEnabled) return
        val screenId = ScreenDeepLinks.kebabId(Routes.SpendingHwSign::class)
        val uri = Uri.parse("bitkit://screen/$screenId/hardware-wallet/100000")

        val link = ScreenDeepLinks.spendingHwSignLink(uri)

        assertEquals(SpendingHwSignLink.Valid(walletId = "hardware-wallet", amountSats = 100_000L), link)
    }

    @Test
    fun `spendingHwSignLink returns null when the uri is for another screen`() {
        if (!ScreenDeepLinks.isEnabled) return

        assertNull(ScreenDeepLinks.spendingHwSignLink(Uri.parse("bitkit://screen/settings")))
    }

    @Test
    fun `spendingHwSignLink reports malformed arguments rather than falling through to nav`() {
        // Falling through would navigate to a sign screen with no quote, which bounces the user home.
        if (!ScreenDeepLinks.isEnabled) return

        for (uri in listOf(
            "bitkit://screen/spending-hw-sign/hardware-wallet",
            "bitkit://screen/spending-hw-sign/hw/abc",
            "bitkit://screen/spending-hw-sign/hw/0",
            "bitkit://screen/spending-hw-sign/hw/-1",
        )) {
            assertEquals(SpendingHwSignLink.Malformed, ScreenDeepLinks.spendingHwSignLink(Uri.parse(uri)), uri)
        }
    }

    @Test
    fun `spendingHwSignLink returns null while screen deep links are disabled`() {
        if (ScreenDeepLinks.isEnabled) return
        val uri = Uri.parse("bitkit://screen/spending-hw-sign/hardware-wallet/100000")

        assertNull(ScreenDeepLinks.spendingHwSignLink(uri))
    }

    @Test
    fun `a route with both argument kinds keeps the required one in the path`() {
        if (!ScreenDeepLinks.isEnabled) return
        val links = ScreenDeepLinks.linksFor(Routes.ActivityDetail::class)

        assertEquals("bitkit://screen/activity-detail/{id}?walletId={walletId}", links.single().uriPattern)
    }

    @Test
    fun `arguments with defaults are appended as query parameters`() {
        if (!ScreenDeepLinks.isEnabled) return
        val links = ScreenDeepLinks.linksFor(Routes.Contacts::class)

        assertEquals(
            "bitkit://screen/contacts?showAddContactSheet={showAddContactSheet}",
            links.single().uriPattern,
        )
    }

    @Test
    fun `every route declares whether it may be entered directly`() {
        val markers = setOf(Routes.DeepLinkable::class, Routes.InternalOnly::class)

        val undeclared = Routes::class.sealedSubclasses.filterNot { it in markers }

        assertTrue(undeclared.isEmpty(), "routes missing a DeepLinkable or InternalOnly decision: $undeclared")
    }

    @Test
    fun `every deep-linkable route has a unique screen id`() {
        val ids = mutableMapOf<String, String>()

        Routes.DeepLinkable::class.sealedSubclasses.forEach { route ->
            val name = route.simpleName.orEmpty()
            val id = ScreenDeepLinks.screenId(route)

            assertNotNull(id, "route $name has no screen id")
            val clash = ids.put(id, name)
            assertNull(clash, "screen id '$id' is used by both $clash and $name")
        }
    }

    @Test
    fun `internal routes are not deep-linkable`() {
        val internal = Routes.InternalOnly::class.sealedSubclasses
        val leaked = internal.filter { Routes.DeepLinkable::class.java.isAssignableFrom(it.java) }

        assertTrue(internal.isNotEmpty())
        assertTrue(leaked.isEmpty(), "internal routes exposed as deep-linkable: $leaked")
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

    @Test
    fun `screen uris are detached from the activity intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bitkit://screen/settings"))

        val detached = ScreenDeepLinks.detachScreenUri(intent)

        assertTrue(detached)
        assertNull(intent.data)
    }
}
