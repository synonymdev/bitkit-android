package to.bitkit.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Keeps `docs/screens-map.md` in sync with the `*Screen.kt` files: the phone app's in `app/src/main/java` against the
 * sections before the Wear OS heading, the watch app's in `wear/src/main/java` against the Wear OS section. A file
 * name both apps use needs a row in each.
 */
class ScreensMapTest {
    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/screens-map.md").isFile }
    private val screensMap = File(projectRoot, "docs/screens-map.md").readText()
    private val phoneMap = screensMap.substringBefore(WEAR_HEADING)
    private val wearMap = screensMap.substringAfter(WEAR_HEADING, missingDelimiterValue = "")

    @Test
    fun `every phone screen has a map entry and every entry has a screen`() =
        assertInSync(sourceRoot = "app/src/main/java", map = phoneMap)

    @Test
    fun `every watch screen has a map entry in the wear section and every entry there has a screen`() =
        assertInSync(sourceRoot = "wear/src/main/java", map = wearMap)

    private fun assertInSync(sourceRoot: String, map: String) {
        val screens = File(projectRoot, sourceRoot).walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") }
            .map { it.name }
            .toSet()
        val entries = SCREEN_ENTRY.findAll(map)
            .map { it.value }
            .toSet()

        val missing = (screens - entries).sorted()
        val stale = (entries - screens).sorted()

        assertTrue(screens.isNotEmpty(), "No *Screen.kt files found under '$sourceRoot'")
        assertTrue(
            missing.isEmpty() && stale.isEmpty(),
            buildString {
                appendLine("docs/screens-map.md is out of sync with $sourceRoot.")
                if (missing.isNotEmpty()) appendLine("Screens without a map entry: $missing")
                if (stale.isNotEmpty()) appendLine("Map entries without a screen: $stale")
            },
        )
    }

    private companion object {
        const val WEAR_HEADING = "\n## Wear OS\n"
        val SCREEN_ENTRY = Regex("""\b[A-Z]\w*Screen\.kt\b""")
    }
}
