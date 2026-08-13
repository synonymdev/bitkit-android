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
    fun `every route declares whether it may be entered directly`() {
        val markers = setOf(Routes.DeepLinkable::class, Routes.InternalOnly::class)

        val undeclared = Routes::class.sealedSubclasses.filterNot { it in markers }

        assertTrue(undeclared.isEmpty(), "routes missing a DeepLinkable or InternalOnly decision: $undeclared")
    }

    @Test
    fun `every deep-linkable route has a unique screen id and variant-correct links`() {
        val ids = mutableMapOf<String, String>()

        Routes.DeepLinkable::class.sealedSubclasses.forEach { route ->
            val name = route.simpleName.orEmpty()
            val id = ScreenDeepLinks.screenId(route)

            assertNotNull(id, "route $name has no screen id")
            val clash = ids.put(id, name)
            assertNull(clash, "screen id '$id' is used by both $clash and $name")
            val links = ScreenDeepLinks.linksFor(route)
            if (ScreenDeepLinks.isEnabled) {
                assertEquals(1, links.size, "route $name has no deep link")
            } else {
                assertTrue(links.isEmpty(), "route $name leaked a deep link in release")
            }
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
    fun `screen links are enabled only on debug`() {
        assertEquals(ScreenDeepLinks.isEnabled, ScreenDeepLinks.linksFor(Routes.Settings::class).isNotEmpty())
    }

    @Test
    fun `screen uris are detached from the activity intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bitkit://screen/settings"))

        val detached = ScreenDeepLinks.detachScreenUri(intent)

        assertTrue(detached)
        assertNull(intent.data)
    }
}
