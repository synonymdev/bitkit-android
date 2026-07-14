package to.bitkit.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyAuthRequestTest {

    @Test
    fun `parse recognizes watch-only account claim`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1, request.bitkitClaim)
    }

    @Test
    fun `parse preserves normal auth without Bitkit claim`() {
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl("/pub/bitkit.to/:rw"),
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = "/pub/bitkit.to/:rw",
        ).getOrThrow()

        assertNull(request.bitkitClaim)
    }

    @Test
    fun `parse rejects duplicate Bitkit claim`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(
                capabilities,
                PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue,
                PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue,
            ),
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.DuplicateBitkitClaim>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects unknown Bitkit claim`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, "unknown-v1"),
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        val error = assertIs<PubkyAuthRequestError.UnsupportedBitkitClaim>(result.exceptionOrNull())
        assertEquals("unknown-v1", error.value)
    }

    @Test
    fun `parse rejects watch-only claim with other capabilities`() {
        val capabilities = "/pub/paykit/v0/:rw"
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(result.exceptionOrNull())
    }

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

    private fun authUrl(capabilities: String, vararg claimValues: String): String {
        val claims = claimValues.joinToString(separator = "") {
            "&${PubkyAuthClaim.QUERY_PARAMETER}=$it"
        }
        return "pubkyauth://signin?caps=$capabilities&relay=https%3A%2F%2Fhttprelay.pubky.app%2Finbox%2F$claims"
    }
}
