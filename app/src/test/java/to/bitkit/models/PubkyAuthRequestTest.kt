package to.bitkit.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyAuthRequestTest {

    @Test
    fun `parseCapabilities parses single permission`() {
        val permissions = PubkyAuthRequest.parseCapabilities("/pub/bitkit.to/:rw")

        assertEquals(1, permissions.size)
        assertEquals("/pub/bitkit.to/", permissions[0].path)
        assertEquals("rw", permissions[0].accessLevel)
    }

    @Test
    fun `parseCapabilities parses multiple permissions`() {
        val caps = "/pub/bitkit.to/:rw,/pub/pubky.app/:r,/pub/paykit/v0/:rw"
        val permissions = PubkyAuthRequest.parseCapabilities(caps)

        assertEquals(3, permissions.size)
        assertEquals("/pub/bitkit.to/", permissions[0].path)
        assertEquals("rw", permissions[0].accessLevel)
        assertEquals("/pub/pubky.app/", permissions[1].path)
        assertEquals("r", permissions[1].accessLevel)
        assertEquals("/pub/paykit/v0/", permissions[2].path)
        assertEquals("rw", permissions[2].accessLevel)
    }

    @Test
    fun `parseCapabilities handles empty string`() {
        assertTrue(PubkyAuthRequest.parseCapabilities("").isEmpty())
    }

    @Test
    fun `parseCapabilities skips malformed segments`() {
        val permissions = PubkyAuthRequest.parseCapabilities("malformed,/pub/ok/:r")

        assertEquals(1, permissions.size)
        assertEquals("/pub/ok/", permissions[0].path)
    }

    @Test
    fun `displayAccess maps r to READ`() {
        val perm = PubkyAuthPermission(path = "/pub/test/", accessLevel = "r")
        assertEquals("READ", perm.displayAccess)
    }

    @Test
    fun `displayAccess maps w to WRITE`() {
        val perm = PubkyAuthPermission(path = "/pub/test/", accessLevel = "w")
        assertEquals("WRITE", perm.displayAccess)
    }

    @Test
    fun `displayAccess maps rw to READ, WRITE`() {
        val perm = PubkyAuthPermission(path = "/pub/test/", accessLevel = "rw")
        assertEquals("READ, WRITE", perm.displayAccess)
    }

    @Test
    fun `extractServiceName extracts from pub path`() {
        assertEquals("bitkit.to", PubkyAuthRequest.extractServiceName("/pub/bitkit.to/"))
        assertEquals("pubky.app", PubkyAuthRequest.extractServiceName("/pub/pubky.app/"))
        assertEquals("paykit", PubkyAuthRequest.extractServiceName("/pub/paykit/v0/"))
    }

    @Test
    fun `extractServiceName returns null for invalid path`() {
        assertNull(PubkyAuthRequest.extractServiceName("/invalid"))
        assertNull(PubkyAuthRequest.extractServiceName(""))
    }

    @Test
    fun `extractServiceName handles staging prefix`() {
        assertEquals(
            "staging.bitkit.to",
            PubkyAuthRequest.extractServiceName("/pub/staging.bitkit.to/profile.json"),
        )
    }
}
