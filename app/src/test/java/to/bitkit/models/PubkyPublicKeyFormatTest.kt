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
            "  PUBKY3RSDUHCXPW74SNWYCT86M38C63J3PQ8X4YCQIKXG64ROIK8YW5XGextra  "

        val bounded = PubkyPublicKeyFormat.bounded(overlongInput)

        assertEquals(PubkyPublicKeyFormat.maximumInputLength, bounded.length)
        assertEquals("pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", bounded)
    }

    @Test
    fun `normalized accepts prefixed and unprefixed keys`() {
        val rawKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val prefixedKey = "pubky$rawKey"

        assertEquals(prefixedKey, PubkyPublicKeyFormat.normalized(rawKey))
        assertEquals(prefixedKey, PubkyPublicKeyFormat.normalized(prefixedKey))
    }

    @Test
    fun `normalized rejects invalid keys`() {
        assertNull(PubkyPublicKeyFormat.normalized("pubkyshort"))
        assertNull(
            PubkyPublicKeyFormat.normalized(
                "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5x0",
            ),
        )
    }

    @Test
    fun `redacted shortens normalized pubky keys`() {
        val rawKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

        assertEquals("pubky3r…k8yw5xg", PubkyPublicKeyFormat.redacted(rawKey))
    }

    @Test
    fun `matches compares equivalent pubky representations`() {
        val rawKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val prefixedKey = "pubky$rawKey"

        assertTrue(PubkyPublicKeyFormat.matches(rawKey, prefixedKey))
        assertFalse(PubkyPublicKeyFormat.matches(prefixedKey, "pubkyinvalid"))
    }
}
