package to.bitkit.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyProfileDataTest {

    @Test
    fun `encode and decode round-trip preserves all fields`() {
        val data = PubkyProfileData(
            name = "Satoshi",
            bio = "Bitcoin creator",
            image = "pubky://abc/pub/bitkit.to/blobs/123.jpg",
            links = listOf(PubkyProfileDataLink("Website", "https://bitcoin.org")),
            tags = listOf("Founder", "Bitcoin"),
        )
        val json = data.encode().toString(Charsets.UTF_8)
        val decoded = PubkyProfileData.decode(json)

        assertEquals("Satoshi", decoded.name)
        assertEquals("Bitcoin creator", decoded.bio)
        assertEquals("pubky://abc/pub/bitkit.to/blobs/123.jpg", decoded.image)
        assertEquals(1, decoded.links.size)
        assertEquals("Website", decoded.links[0].label)
        assertEquals("https://bitcoin.org", decoded.links[0].url)
        assertEquals(listOf("Founder", "Bitcoin"), decoded.tags)
    }

    @Test
    fun `decode with missing tags field defaults to empty list`() {
        val json = """{"name":"Alice","bio":"hello","image":null,"links":[]}"""
        val decoded = PubkyProfileData.decode(json)

        assertEquals("Alice", decoded.name)
        assertTrue(decoded.tags.isEmpty())
    }

    @Test
    fun `decode with missing image field defaults to null`() {
        val json = """{"name":"Bob","bio":"","links":[],"tags":["Dev"]}"""
        val decoded = PubkyProfileData.decode(json)

        assertNull(decoded.image)
        assertEquals(listOf("Dev"), decoded.tags)
    }

    @Test
    fun `toPubkyProfile converts correctly`() {
        val data = PubkyProfileData(
            name = "Satoshi",
            bio = "Bio",
            image = "pubky://img",
            links = listOf(PubkyProfileDataLink("X", "https://x.com/s")),
            tags = listOf("Founder"),
        )
        val profile = data.toPubkyProfile("pubkyabc123")

        assertEquals("pubkyabc123", profile.publicKey)
        assertEquals("Satoshi", profile.name)
        assertEquals("Bio", profile.bio)
        assertEquals("pubky://img", profile.imageUrl)
        assertEquals(1, profile.links.size)
        assertEquals("X", profile.links[0].label)
        assertEquals(listOf("Founder"), profile.tags)
        assertNull(profile.status)
    }

    @Test
    fun `PubkyProfile toProfileData converts correctly`() {
        val profile = PubkyProfile(
            publicKey = "pubkyabc",
            name = "Alice",
            bio = "Hello",
            imageUrl = "pubky://img",
            links = listOf(PubkyProfileLink("Email", "alice@example.com")),
            tags = listOf("Designer"),
            status = null,
        )
        val data = profile.toProfileData()

        assertEquals("Alice", data.name)
        assertEquals("Hello", data.bio)
        assertEquals("pubky://img", data.image)
        assertEquals(1, data.links.size)
        assertEquals("Email", data.links[0].label)
        assertEquals(listOf("Designer"), data.tags)
    }
}
