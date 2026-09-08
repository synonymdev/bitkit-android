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
    fun `pubkyauth alias is disabled by default`() {
        val alias = manifest.getElementsByTagName("activity-alias").elements()
            .single { it.getAttribute("android:name") == ".ui.MainActivityPubkyAuth" }

        assertEquals(".ui.MainActivity", alias.getAttribute("android:targetActivity"))
        assertEquals("false", alias.getAttribute("android:enabled"))
        assertEquals("true", alias.getAttribute("android:exported"))
        assertTrue(alias.handlesScheme("pubkyauth"))
    }

    @Test
    fun `signup links resolve only through the enabled Pubky alias`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = application.packageManager
        val alias = ComponentName(application.packageName, "to.bitkit.ui.MainActivityPubkyAuth")
        val signup = Intent(Intent.ACTION_VIEW, "pubkyring://signup?hs=homeserver".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(application.packageName)

        assertTrue(packageManager.queryIntentActivities(signup, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())

        packageManager.setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        listOf("pubkyring://signup?hs=homeserver", "pubkyauth://direct_signup?hs=homeserver", "pubkyauth://?caps=rw")
            .forEach {
                val resolved = packageManager.queryIntentActivities(
                    Intent(signup).setData(it.toUri()),
                    PackageManager.MATCH_DEFAULT_ONLY,
                ).single().activityInfo
                assertEquals(alias.className, resolved.name)
                assertEquals("to.bitkit.ui.MainActivity", resolved.targetActivity)
                assertTrue(resolved.exported)
            }

        val signIn = Intent(signup).setData("pubkyring://auth".toUri())
        assertTrue(packageManager.queryIntentActivities(signIn, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())

        packageManager.setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        assertTrue(packageManager.queryIntentActivities(signup, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())
    }

    private fun parseManifest(path: Path) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(path.toFile())

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.handlesScheme(scheme: String): Boolean =
        getElementsByTagName("data").elements().any { it.getAttribute("android:scheme") == scheme }
}
