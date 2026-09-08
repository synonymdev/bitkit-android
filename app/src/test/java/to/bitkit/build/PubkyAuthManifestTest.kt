package to.bitkit.build

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PubkyAuthManifestTest {
    private val repoRoot = generateSequence(
        Path(requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }),
    ) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    private val manifest by lazy { parseManifest(repoRoot.resolve("app/src/main/AndroidManifest.xml")) }

    @Test
    fun `main activity does not handle pubkyauth`() {
        val mainActivity = manifest.getElementsByTagName("activity").elements()
            .single { it.getAttribute("android:name") == ".ui.MainActivity" }

        assertFalse(mainActivity.handlesScheme("pubkyauth"))
    }

    @Test
    fun `Pubky aliases are disabled by default`() {
        val aliases = manifest.getElementsByTagName("activity-alias").elements()
        listOf(".ui.MainActivityPubkyAuth", ".ui.MainActivityPubkySignup").forEach { name ->
            val alias = aliases.single { it.getAttribute("android:name") == name }
            assertEquals(".ui.MainActivity", alias.getAttribute("android:targetActivity"))
            assertEquals("false", alias.getAttribute("android:enabled"))
            assertEquals("true", alias.getAttribute("android:exported"))
            assertTrue(alias.handlesScheme("pubkyauth"))
        }
    }

    @Test
    fun `signup and authorization links resolve only through their enabled aliases`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = application.packageManager
        val authAlias = ComponentName(application.packageName, "to.bitkit.ui.MainActivityPubkyAuth")
        val signupAlias = ComponentName(application.packageName, "to.bitkit.ui.MainActivityPubkySignup")
        val signupUrls = listOf(
            "pubkyring://signup?hs=homeserver",
            "pubkyauth://signup?hs=homeserver&relay=relay&secret=secret&caps=rw",
            "pubkyauth://signup?hs=homeserver",
            "pubkyauth://direct_signup?hs=homeserver",
        )
        val authUrls = listOf("pubkyauth://?caps=rw", "pubkyauth://signin?caps=rw", "pubkyauth://grant?caps=rw")
        val unrelatedUrls = listOf("pubkyring://auth", "pubkyring://direct_signup")
        val allUrls = signupUrls + authUrls + unrelatedUrls

        assertRoutes(packageManager, application.packageName, allUrls, null)
        packageManager.setComponentEnabledSetting(
            signupAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        assertRoutes(packageManager, application.packageName, signupUrls, signupAlias)
        assertRoutes(packageManager, application.packageName, authUrls + unrelatedUrls, null)

        packageManager.setComponentEnabledSetting(
            signupAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            authAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        assertRoutes(
            packageManager,
            application.packageName,
            signupUrls.filter { it.startsWith("pubkyauth:") } + authUrls,
            authAlias,
        )
        assertRoutes(packageManager, application.packageName, listOf(signupUrls.first()) + unrelatedUrls, null)

        packageManager.setComponentEnabledSetting(
            authAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        assertRoutes(packageManager, application.packageName, allUrls, null)
    }

    private fun assertRoutes(
        packageManager: PackageManager,
        packageName: String,
        urls: List<String>,
        alias: ComponentName?,
    ) {
        urls.forEach {
            val intent = Intent(Intent.ACTION_VIEW, it.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(packageName)
            val resolved = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (alias == null) {
                assertTrue(resolved.isEmpty(), it)
            } else {
                val activity = resolved.single().activityInfo
                assertEquals(alias.className, activity.name, it)
                assertEquals("to.bitkit.ui.MainActivity", activity.targetActivity)
                assertTrue(activity.exported)
            }
        }
    }

    private fun parseManifest(path: Path) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(path.toFile())

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.handlesScheme(scheme: String): Boolean =
        getElementsByTagName("data").elements().any { it.getAttribute("android:scheme") == scheme }
}
