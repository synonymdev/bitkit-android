package to.bitkit.build

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun parseManifest(path: Path) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(path.toFile())

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.handlesScheme(scheme: String): Boolean =
        getElementsByTagName("data").elements().any { it.getAttribute("android:scheme") == scheme }
}
