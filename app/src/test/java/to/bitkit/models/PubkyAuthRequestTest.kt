package to.bitkit.models

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyAuthRequestTest {

    @Test
    fun `parse Ring signup preserves registration and authorization details`() {
        val request = PubkyAuthRequest.parseSignup(ringSignupUrl("invite code")).getOrThrow()

        assertTrue(request.isSignup)
        assertEquals("homeserver", request.homeserverPublicKey)
        assertEquals("invite code", request.signupToken)
        assertEquals("https://relay.example/inbox/", request.relay)
        assertEquals("/pub/example.app/:rw", request.capabilities)
        assertEquals(
            "pubkyauth:///?relay=https%3A%2F%2Frelay.example%2Finbox%2F" +
                "&secret=secret&caps=%2Fpub%2Fexample.app%2F%3Arw",
            request.authorizationUrl,
        )
    }

    @Test
    fun `parse direct signup accepts canonical and legacy formats`() {
        listOf("direct_signup", "signup").forEach { action ->
            val request = PubkyAuthRequest.parseSignup(directSignupUrl(action, "invite code")).getOrThrow()

            assertTrue(request.isSignup)
            assertEquals("homeserver", request.homeserverPublicKey)
            assertEquals("invite code", request.signupToken)
            assertEquals("", request.relay)
            assertEquals("", request.capabilities)
            assertNull(request.authorizationUrl)
        }
    }

    @Test
    fun `parse Ring signup rejects missing and duplicate required values`() {
        val invalidUrls = listOf(
            ringSignupUrl().replace("&secret=secret", ""),
            "${ringSignupUrl()}&hs=other",
        )

        invalidUrls.forEach { url ->
            assertIs<PubkyAuthRequestError.InvalidUrl>(PubkyAuthRequest.parseSignup(url).exceptionOrNull())
        }
    }

    @Test
    fun `parse recognizes watch-only account claim`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1, request.bitkitClaim)
    }

    @Test
    fun `parse recognizes watch-only account claim with reordered capabilities`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES.split(",").reversed().joinToString(",")
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1, request.bitkitClaim)
    }

    @Test
    fun `matcher recognizes watch-only account claim with capability whitespace`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES.replace(",", " , ")

        assertTrue(PubkyAuthClaim.matchesWatchOnlyAccountCapabilities(capabilities))
    }

    @Test
    fun `parse preserves normal auth without Bitkit claim`() {
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl("/pub/bitkit.to/:rw"),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = "/pub/bitkit.to/:rw",
        ).getOrThrow()

        assertFalse(request.isSignup)
        assertEquals("paykit.test", request.clientId)
        assertNull(request.bitkitClaim)
    }

    @Test
    fun `parse deduplicates service name across public and private capabilities`() {
        val capabilities = "/pub/locks.app/:rw,/priv/locks.app/:rw"

        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(listOf("/pub/locks.app/", "/priv/locks.app/"), request.permissions.map { it.path })
        assertEquals(listOf("locks.app"), request.serviceNames)
    }

    @Test
    fun `parse deduplicates service names across multiple paths in first-seen order`() {
        val capabilities =
            "/pub/locks.app/posts/:r,/pub/example.app/:r,/priv/locks.app/settings/:w,/priv/example.app/cache/:r"

        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(4, request.permissions.size)
        assertEquals(listOf("locks.app", "example.app"), request.serviceNames)
    }

    @Test
    fun `parse rejects watch-only capability without Bitkit claim`() {
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.MissingBitkitClaim>(result.exceptionOrNull())
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
            clientId = "paykit.test",
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
            clientId = "paykit.test",
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
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects watch-only claim without private capability`() {
        val capabilities = "/pub/paykit/v0/bitkit/server/:rw"
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects watch-only claim with empty capability`() {
        val capabilities = "${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES},"
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue),
            clientId = "paykit.test",
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
    fun `displayPath removes capability separator`() {
        val perm = PubkyAuthPermission(path = "/pub/paykit/v0/bitkit/server/", accessLevel = "rw")
        assertEquals("/pub/paykit/v0/bitkit/server", perm.displayPath)
    }

    @Test
    fun `displayPath preserves root`() {
        val perm = PubkyAuthPermission(path = "/", accessLevel = "r")
        assertEquals("/", perm.displayPath)
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

    private fun ringSignupUrl(signupToken: String? = null): String =
        "pubkyring://signup?hs=homeserver" +
            "&relay=https%3A%2F%2Frelay.example%2Finbox%2F" +
            "&secret=secret&caps=%2Fpub%2Fexample.app%2F%3Arw" +
            signupToken?.let { "&st=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()

    private fun directSignupUrl(action: String, signupToken: String? = null): String =
        "pubkyauth://$action?hs=homeserver" +
            signupToken?.let { "&st=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
}
