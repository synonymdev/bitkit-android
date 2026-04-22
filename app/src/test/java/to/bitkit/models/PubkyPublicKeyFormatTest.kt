package to.bitkit.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyPublicKeyFormatTest {

    @Test
    fun `bounded trims lowercases and caps input`() {
        val overlongInput =
            "  PUBKYYBNDRFG8EJKMCPQXOT1UWISZA345H769YBNDRFG8EJKMCPQXOT1Uextra  "

        val bounded = PubkyPublicKeyFormat.bounded(overlongInput)

        assertEquals(PubkyPublicKeyFormat.maximumInputLength, bounded.length)
        assertEquals("pubkyybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u", bounded)
    }

    @Test
    fun `normalized accepts prefixed and unprefixed keys`() {
        val rawKey = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        val prefixedKey = "pubky$rawKey"

        assertEquals(prefixedKey, PubkyPublicKeyFormat.normalized(rawKey))
        assertEquals(prefixedKey, PubkyPublicKeyFormat.normalized(prefixedKey))
    }

    @Test
    fun `normalized rejects invalid keys`() {
        assertNull(PubkyPublicKeyFormat.normalized("pubkyshort"))
        assertNull(
            PubkyPublicKeyFormat.normalized(
                "pubkyybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot10",
            ),
        )
    }

    @Test
    fun `matches compares equivalent pubky representations`() {
        val rawKey = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        val prefixedKey = "pubky$rawKey"

        assertTrue(PubkyPublicKeyFormat.matches(rawKey, prefixedKey))
        assertFalse(PubkyPublicKeyFormat.matches(prefixedKey, "pubkyinvalid"))
    }
}
