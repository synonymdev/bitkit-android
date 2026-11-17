package to.bitkit.ui.components

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddLinkSheetTest {
    @Test
    fun `web links are accepted with or without their scheme`() {
        listOf("https://example.com", "http://example.com/a?b=c", "example.com", " www.example.com/path ").forEach {
            assertTrue(it.isWebLink(), it)
        }
    }

    @Test
    fun `links a social v1 profile rejects are refused`() {
        listOf(
            "mailto:a@b.c", "a@b.c", "tel:+123456789", "+1 234 567 890", "123.456.7890",
            "ftp://example.com", "example", "two words.com", "",
        ).forEach {
            assertFalse(it.isWebLink(), it)
        }
    }
}
