package to.bitkit.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Keeps `docs/screens-map.md` in sync with the `*Screen.kt` files in `app/src/main/java`.
 */
class ScreensMapTest {
    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/screens-map.md").isFile }
    private val screensMap = File(projectRoot, "docs/screens-map.md")
    private val sourceRoot = File(projectRoot, "app/src/main/java")

    @Test
    fun `every screen has a map entry and every entry has a screen`() {
        val screens = sourceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") }
            .map { it.name }
            .toSet()
        val entries = SCREEN_ENTRY.findAll(screensMap.readText())
            .map { it.value }
            .toSet()

        val missing = (screens - entries).sorted()
        val stale = (entries - screens).sorted()

        assertTrue(screens.isNotEmpty(), "No *Screen.kt files found under '$sourceRoot'")
        assertTrue(
            missing.isEmpty() && stale.isEmpty(),
            buildString {
                appendLine("docs/screens-map.md is out of sync with app/src/main/java.")
                if (missing.isNotEmpty()) appendLine("Screens without a map entry: $missing")
                if (stale.isNotEmpty()) appendLine("Map entries without a screen: $stale")
            },
        )
    }

    private companion object {
        val SCREEN_ENTRY = Regex("""\b[A-Z]\w*Screen\.kt\b""")
    }
}
