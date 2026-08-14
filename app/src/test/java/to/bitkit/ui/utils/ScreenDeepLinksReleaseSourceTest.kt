package to.bitkit.ui.utils

import org.junit.Test
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenDeepLinksReleaseSourceTest : BaseUnitTest() {
    @Test
    fun `release runtime source stays disabled`() {
        val source = releaseRuntimeSource()

        assertTrue("val isEnabled = false" in source)
        assertFalse("val isEnabled = true" in source)
        assertTrue("= emptyList()" in source)
        assertTrue("fun sheetFor" in source)
        assertTrue("= null" in source)
        assertFalse("navDeepLink" in source)
    }

    private fun releaseRuntimeSource(): String {
        val file = listOf(
            File("src/release/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt"),
            File("app/src/release/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt"),
        ).firstOrNull { it.isFile }

        assertNotNull(file, "release ScreenDeepLinkRuntime.kt not found")
        return file.readText()
    }
}
