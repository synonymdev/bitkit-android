package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PubkyAppLinkTest {
    @Test
    fun `labels are limited to 100 code points, emoji counted once`() {
        val emoji = "🚀"

        assertNull(pubkyAppLinkError(emoji.repeat(100), "example.com", webOnly = true))
        assertEquals(
            PubkyAppLinkError.LABEL_TOO_LONG,
            pubkyAppLinkError(emoji.repeat(101), "example.com", webOnly = true),
        )
        assertNull(pubkyAppLinkError("  " + "a".repeat(100) + "\n", "example.com", webOnly = true))
    }

    @Test
    fun `urls are limited to 300 code points after normalization`() {
        val emoji = "🚀"
        val at300 = "https://example.com/" + "a".repeat(280)
        val bareAt301 = "example.com/" + "a".repeat(281)

        assertEquals(300, at300.length)
        assertNull(pubkyAppLinkError("Site", at300, webOnly = true))
        assertEquals(PubkyAppLinkError.URL_TOO_LONG, pubkyAppLinkError("Site", "${at300}a", webOnly = true))
        assertEquals(PubkyAppLinkError.URL_TOO_LONG, pubkyAppLinkError("Site", bareAt301, webOnly = true))
        assertNull(pubkyAppLinkError("Site", "https://example.com/" + emoji.repeat(280), webOnly = true))
        assertEquals(
            PubkyAppLinkError.URL_TOO_LONG,
            pubkyAppLinkError("Site", "https://example.com/" + emoji.repeat(281), webOnly = true),
        )
    }

    @Test
    fun `invalid urls are refused`() {
        listOf("not a url", "example", "https://", "https://exa mple.com", "https://1.2.3.999").forEach {
            assertEquals(PubkyAppLinkError.URL_INVALID, pubkyAppLinkError("Site", it, webOnly = false), it)
        }
    }

    @Test
    fun `normalizable inputs are accepted`() {
        listOf("example.com", " www.example.com/path ", "https://Example.com", "HTTPS://example.com").forEach {
            assertNull(pubkyAppLinkError("Site", it, webOnly = true), it)
        }
        listOf("me@example.com", "+1 (234) 567-890", "mailto:me@example.com", "tel:+123456789").forEach {
            assertNull(pubkyAppLinkError("Contact", it, webOnly = false), it)
        }
    }

    @Test
    fun `web only links refuse email and phone`() {
        listOf("me@example.com", "+1 234 567 890", "mailto:me@example.com", "ftp://example.com").forEach {
            assertEquals(PubkyAppLinkError.URL_INVALID, pubkyAppLinkError("Contact", it, webOnly = true), it)
        }
    }

    @Test
    fun `a blank url is left to the caller`() {
        assertNull(pubkyAppLinkError("Site", "  ", webOnly = true))
    }

    @Test
    fun `characters the url parser accepts in a path or query are accepted`() {
        listOf(
            "https://example.com/?q=a|b",
            "https://example.com/100%",
            "https://example.com/a[1]{2}^\"x\"",
            "https://bücher.de",
            "https://my_site.example.com",
        ).forEach {
            assertNull(pubkyAppLinkError("Site", it, webOnly = true), it)
            assertEquals(it, it.toPubkyAppLinkUrl(), it)
        }
        assertEquals("https://example.com/?q=a|b", "example.com/?q=a|b".toPubkyAppLinkUrl())
    }

    @Test
    fun `hosts and ports the url parser refuses are refused`() {
        listOf(
            "https://exa|mple.com",
            "https://exa%mple.com",
            "https://",
            "https://example.com:65536",
            "https://example.com:port",
            "https://[zz::1]",
            "https://example.com/\u0001",
        ).forEach {
            assertEquals(PubkyAppLinkError.URL_INVALID, pubkyAppLinkError("Site", it, webOnly = false), it)
        }
        assertNull(pubkyAppLinkError("Site", "https://example.com:65535", webOnly = false))
        assertNull(pubkyAppLinkError("Site", "https://example.com.", webOnly = true))
        assertNull(pubkyAppLinkError("Site", "https://1.2.3.4.", webOnly = false))
        assertNull(pubkyAppLinkError("Site", "https:example.com", webOnly = false))
        assertEquals(PubkyAppLinkError.URL_INVALID, pubkyAppLinkError("Site", "https:example.com", webOnly = true))
        assertNull(pubkyAppLinkError("Site", "https://[::1]:8080", webOnly = false))
    }

    @Test
    fun `a label empty once on one line is refused after the url`() {
        assertEquals(PubkyAppLinkError.LABEL_EMPTY, pubkyAppLinkError("\u0001\u0002", "example.com", webOnly = true))
        assertEquals(PubkyAppLinkError.LABEL_EMPTY, pubkyAppLinkError("", "example.com", webOnly = true))
        assertEquals(PubkyAppLinkError.URL_INVALID, pubkyAppLinkError("", "not a url", webOnly = true))
    }

    @Test
    fun `links normalize to the url pubky app stores`() {
        assertEquals("https://example.com", "example.com".toPubkyAppLinkUrl())
        assertEquals("mailto:me@example.com", "me@example.com".toPubkyAppLinkUrl())
        assertEquals("tel:+1234567890", "+1 (234) 567-890".toPubkyAppLinkUrl())
        assertEquals("https://Example.com", "HTTPS://Example.com".toPubkyAppLinkUrl())
        assertNull("not a url".toPubkyAppLinkUrl())
    }
}
