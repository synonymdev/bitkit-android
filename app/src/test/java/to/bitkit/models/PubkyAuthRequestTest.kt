package to.bitkit.models

import kotlinx.serialization.json.Json
import to.bitkit.models.PubkyAuthClaim.Item
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyAuthRequestTest {

    @Test
    fun `optional USDT omission preserves delegated Paykit authority`() {
        val claim = requireNotNull(PubkyAuthClaim.fromWireValue("paykit-access-v1.usdt-address-v1"))
        val payload = PubkyAuthClaimCodec.encode(claim, "{}".encodeToByteArray(), 3uL, ByteArray(32) { 11 })
        val actual = Json.parseToJsonElement(payload.decodeToString())
        val expected = Json.parseToJsonElement(
            """{"paykit_access":{"key_generation":3,"secret":"CwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCws"}}"""
        )
        assertEquals(expected, actual)
        assertTrue(claim.sharesUsdt)
        assertFalse(claim.sharesBitcoin)
    }

    @Test
    fun `parse preserves each companion selection and received item order`() {
        val expected = mapOf(
            "watch-only-account-v1" to listOf(Item.WATCH_ONLY_ACCOUNT_V1),
            "paykit-access-v1" to listOf(Item.PAYKIT_ACCESS_V1),
            "paykit-access-v1.watch-only-account-v1" to listOf(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1),
            "watch-only-account-v1.paykit-access-v1" to listOf(Item.WATCH_ONLY_ACCOUNT_V1, Item.PAYKIT_ACCESS_V1),
        )
        for ((wireValue, items) in expected) {
            val claim = requireNotNull(
                PubkyAuthRequest.parseBitkitClaim(
                    authUrl("/pub/paykit/:rw", wireValue),
                    "/pub/paykit/:rw",
                ).getOrThrow(),
            )
            assertEquals(items, claim.items)
            assertEquals(wireValue, claim.wireValue)
            assertEquals(Item.PAYKIT_ACCESS_V1 in items, claim.includesPaykitAccess)
            assertEquals(Item.WATCH_ONLY_ACCOUNT_V1 in items, claim.includesWatchOnlyAccount)
        }
    }

    @Test
    fun `constructor copies items in canonical order and rejects empty or duplicate selections`() {
        val items = arrayOf(Item.WATCH_ONLY_ACCOUNT_V1, Item.PAYKIT_ACCESS_V1)
        val claim = PubkyAuthClaim(*items)
        items[0] = Item.PAYKIT_ACCESS_V1

        assertEquals(listOf(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1), claim.items)
        assertEquals("paykit-access-v1.watch-only-account-v1", claim.wireValue)
        assertFailsWith<IllegalArgumentException> { PubkyAuthClaim() }
        for (item in Item.entries) {
            assertFailsWith<IllegalArgumentException> { PubkyAuthClaim(item, item) }
        }
    }

    @Test
    fun `parse rejects empty duplicate unknown and combined identifiers`() {
        val invalid = listOf(
            "", ".", ".paykit-access-v1", "paykit-access-v1.",
            "paykit-access-v1..watch-only-account-v1",
            "paykit-access-v1.paykit-access-v1", "watch-only-account-v1.watch-only-account-v1",
            "paykit-access-v1.watch-only-account-v1.paykit-access-v1",
            "unknown-v1", "paykit-access-v1.unknown-v1", "unknown-v1.watch-only-account-v1",
            "paykit-access-and-watch-only-account-v1", "PAYKIT-ACCESS-V1", "paykit-access-v1%20",
        )
        for (wireValue in invalid) {
            assertIs<PubkyAuthRequestError.UnsupportedBitkitClaim>(
                PubkyAuthRequest.parseBitkitClaim(authUrl("/pub/paykit/:rw", wireValue), "/pub/paykit/:rw")
                    .exceptionOrNull(),
                wireValue,
            )
        }
    }

    @Test
    fun `parse rejects duplicate mixed or encoded companion parameters`() {
        for (claim in companionSelections()) {
            for (other in companionSelections()) {
                val url = authUrl("/pub/paykit/:rw", claim.wireValue) + "&x-bitkit-%63laim=${other.wireValue}"
                assertIs<PubkyAuthRequestError.DuplicateBitkitClaim>(
                    PubkyAuthRequest.parseBitkitClaim(url, "/pub/paykit/:rw").exceptionOrNull(),
                )
            }
        }
    }

    @Test
    fun `all companion claims require the exact Paykit scope`() {
        val invalidCapabilities = listOf(
            "/pub/paykit/:r",
            "/pub/paykit/v0/:rw",
            "/pub/:rw",
            "/pub/paykit/:rw,/pub/other/:rw",
            "/pub/paykit/:rw,/pub/paykit/:rw",
        )
        for (claim in companionSelections()) {
            for (capabilities in invalidCapabilities) {
                assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(
                    PubkyAuthRequest.parseBitkitClaim(authUrl(capabilities, claim.wireValue), capabilities)
                        .exceptionOrNull(),
                )
            }
        }
    }

    @Test
    fun `parse authorized signup preserves registration and authorization details`() {
        listOf("pubkyring", "pubkyauth").forEach { scheme ->
            val request = PubkyAuthRequest.parseSignup(ringSignupUrl("invite code", scheme)).getOrThrow()

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
    fun `grant signup preserves the requesting app and creates only a missing identity`() {
        val url = "pubkyauth://signup_grant?hs=homeserver&st=invite%20code&cid=shop.pubky.app"
        val request = PubkyAuthRequest.parseGrantSignup(
            rawUrl = url,
            clientId = "shop.pubky.app",
            relay = "https://relay.example/inbox/",
            capabilities = "/pub/pubky.app/:rw",
            homeserverPublicKey = "homeserver",
        ).getOrThrow()

        assertTrue(request.isSignup)
        assertTrue(request.isGrantSignup)
        assertTrue(request.requiresIdentityCreation(hasIdentity = false))
        assertFalse(request.requiresIdentityCreation(hasIdentity = true))
        assertEquals("homeserver", request.homeserverPublicKey)
        assertEquals("invite code", request.signupToken)
        assertEquals("shop.pubky.app", request.clientId)
        assertEquals(url, request.authorizationUrl)
        assertEquals(listOf(PubkyAuthPermission("/pub/pubky.app/", "rw")), request.permissions)
    }

    @Test
    fun `grant signup rejects ambiguous registration parameters`() {
        listOf("", "hs=homeserver&hs=other", "hs=homeserver&st=one&st=two").forEach { query ->
            assertIs<PubkyAuthRequestError.InvalidUrl>(
                PubkyAuthRequest.parseGrantSignup(
                    rawUrl = "pubkyauth://signup_grant?$query",
                    clientId = "shop.pubky.app",
                    relay = "https://relay.example/inbox/",
                    capabilities = "/pub/pubky.app/:rw",
                    homeserverPublicKey = "homeserver",
                ).exceptionOrNull(),
            )
        }
    }

    @Test
    fun `parse Ring signup rejects missing and duplicate required values`() {
        val invalidUrls = listOf(
            ringSignupUrl().replace("&secret=secret", ""),
            "${ringSignupUrl()}&hs=other",
            directSignupUrl("signup") + "&relay=https%3A%2F%2Frelay.example",
        )

        invalidUrls.forEach { url ->
            assertIs<PubkyAuthRequestError.InvalidUrl>(PubkyAuthRequest.parseSignup(url).exceptionOrNull())
        }
    }

    @Test
    fun `parse recognizes watch-only account claim`() {
        val capabilities = PubkyAuthClaim.REQUIRED_CAPABILITIES
        val request = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        ).getOrThrow()

        assertEquals(PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1), request.bitkitClaim)
    }

    @Test
    fun `relay display url keeps the path and drops user info and fragment`() {
        val origins = mapOf(
            "https://Relay.Example:8443/inbox/" to "https://relay.example:8443/inbox/",
            "https://httprelay.pubky.app/link" to "https://httprelay.pubky.app/link",
            "https://httprelay.pubky.app" to "https://httprelay.pubky.app/",
            "HTTPS://httprelay.pubky.app/link" to "https://httprelay.pubky.app/link",
            "http://localhost:8080/link" to "http://localhost:8080/link",
            "https://relay.example:0/link" to "https://relay.example:0/link",
            "https://relay.example:80/link" to "https://relay.example:80/link",
            "https://evil.example?@relay.example" to "https://evil.example/?@relay.example",
            "https://relay.example/?x=@evil.example" to "https://relay.example/?x=@evil.example",
            "https://evil.example#@relay.example" to "https://evil.example/",
            "https://relay.example/a/.../b/..c/" to "https://relay.example/a/.../b/..c/",
            "https://relay.example/inbox?next=../x" to "https://relay.example/inbox?next=../x",
            "https://[::1]/link" to "https://[::1]/link",
            "https://[2001:db8:0:1::]/link" to "https://[2001:db8:0:1::]/link",
            "http://user@10.0.2.2:15412/link?x=1#y" to "http://10.0.2.2:15412/link?x=1",
            "https://trusted.example@relay.example/in%20box/" to "https://relay.example/in%20box/",
            "https://relay.example/a|b?c=[e]" to "https://relay.example/a|b?c=[e]",
            "https://relay.example?x=1" to "https://relay.example/?x=1",
            "https://relay.example./link" to "https://relay.example./link",
            "https://-re_lay~.1abc/link" to "https://-re_lay~.1abc/link",
            "https://xn--e1aybc.example/link" to "https://xn--e1aybc.example/link",
            "https://[2001:DB8::1]:8443/link" to "https://[2001:db8::1]:8443/link",
            "https://127.0.0.1/link" to "https://127.0.0.1/link",
            "https://evil.example/@httprelay.pubky.app/link" to "https://evil.example/@httprelay.pubky.app/link",
        )

        origins.forEach { (relay, origin) ->
            assertEquals(origin, PubkyAuthRequest.displayRelay(relay), relay)
            val request = PubkyAuthRequest.parse(
                rawUrl = authUrl("/pub/example.app/:rw"),
                clientId = "example.app",
                relay = relay,
                capabilities = "/pub/example.app/:rw",
            ).getOrThrow()

            assertEquals(origin, request.relayDisplayUrl, relay)
        }
    }

    @Test
    fun `relay whose destination cannot be read with certainty makes the request invalid`() {
        val unreadable = listOf(
            "https://evil.example\\@httprelay.pubky.app/link",
            "https://httprelay.pubky.app\\.evil.example/link",
            "https://реле.example/link",
            "https://%65vil.example/link",
            "https://0x7f.1/link",
            "https://2130706433/link",
            "https://1.2.3/link",
            "https://127.0.0.1./link",
            "https://relay.example /link",
            "https://relay\t.example/link",
            "https://relay.example:/link",
            "https://relay.example\n/link",
            "https://relay.example:443/link",
            "http://relay.example:80/link",
            "https://relay.example:65536/link",
            "https://relay.example:08443/link",
            "https://relay.example/inbox/../../other/",
            "https://relay.example/inbox/./x",
            "https://relay.example/inbox/%2e%2e/other/",
            "https://relay.example/inbox/%2E./other/",
            "https://relay.example/inbox/..",
            "https://relay.example/..?x=1",
            "https://relay.example/a{b}",
            "https://relay.example/a?b='c'",
            "https://relay.example/a\"b",
            "https://relay.example/a^b",
            "https://[2001:db8:0:0:0:0:0:1]/link",
            "https://[2001:0db8::1]/link",
            "https://[1:0:0:2:0:0:0:3]/link",
            "https://[::ffff:1.2.3.4]/link",
            "https://[1::2::3]/link",
            "https://[::12345]/link",
            "https://[1:2:3:4:5:6:7:8:9]/link",
            "https:///inbox/",
            "https:/relay.example/inbox/",
            "https:relay.example/inbox/",
            "ftp://relay.example/inbox/",
            "relay.example/inbox/",
            "not a url",
        )

        unreadable.forEach { relay ->
            assertNull(PubkyAuthRequest.displayRelay(relay), relay)
            assertIs<PubkyAuthRequestError.InvalidUrl>(
                PubkyAuthRequest.parse(
                    rawUrl = authUrl("/pub/example.app/:rw"),
                    clientId = "example.app",
                    relay = relay,
                    capabilities = "/pub/example.app/:rw",
                ).exceptionOrNull(),
                relay,
            )
        }
    }

    @Test
    fun `relay is shown whenever a request has one`() {
        val withoutRelay = PubkyAuthRequest.parseSignup(directSignupUrl("direct_signup")).getOrThrow()
        val ringSignup = PubkyAuthRequest.parseSignup(ringSignupUrl()).getOrThrow()

        assertEquals("", withoutRelay.relay)
        assertNull(withoutRelay.relayDisplayUrl)
        assertEquals("https://relay.example/inbox/", ringSignup.relayDisplayUrl)
        assertIs<PubkyAuthRequestError.InvalidUrl>(
            PubkyAuthRequest.parseSignup(
                ringSignupUrl().replace("relay.example", "evil.example%5C%40relay.example"),
            ).exceptionOrNull(),
        )
        assertIs<PubkyAuthRequestError.InvalidUrl>(
            PubkyAuthRequest.parseSignup(ringSignupUrl().replace("relay.example", "rel%C3%A4y.example"))
                .exceptionOrNull(),
        )
    }

    @Test
    fun `matcher recognizes the Paykit scope with surrounding whitespace`() {
        val capabilities = " ${PubkyAuthClaim.REQUIRED_CAPABILITIES} "

        assertTrue(PubkyAuthClaim.matchesRequiredCapabilities(capabilities))
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
    fun `parse permits Paykit authorization without a companion claim`() {
        val capabilities = PubkyAuthClaim.REQUIRED_CAPABILITIES
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertEquals(null, result.getOrThrow().bitkitClaim)
    }

    @Test
    fun `parse rejects duplicate Bitkit claim`() {
        val capabilities = PubkyAuthClaim.REQUIRED_CAPABILITIES
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(
                capabilities,
                PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue,
                PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue,
            ),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.DuplicateBitkitClaim>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects unknown Bitkit claim`() {
        val capabilities = PubkyAuthClaim.REQUIRED_CAPABILITIES
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
            rawUrl = authUrl(capabilities, PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects watch-only claim without write capability`() {
        val capabilities = "/pub/paykit/:r"
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue),
            clientId = "paykit.test",
            relay = "https://httprelay.pubky.app/inbox/",
            capabilities = capabilities,
        )

        assertIs<PubkyAuthRequestError.InvalidBitkitClaimCapabilities>(result.exceptionOrNull())
    }

    @Test
    fun `parse rejects watch-only claim with empty capability`() {
        val capabilities = "${PubkyAuthClaim.REQUIRED_CAPABILITIES},"
        val result = PubkyAuthRequest.parse(
            rawUrl = authUrl(capabilities, PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1).wireValue),
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
        val perm = PubkyAuthPermission(path = "/pub/paykit/", accessLevel = "rw")
        assertEquals("/pub/paykit", perm.displayPath)
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

    private fun companionSelections() = listOf(
        PubkyAuthClaim(Item.PAYKIT_ACCESS_V1),
        PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1),
        PubkyAuthClaim(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1),
        requireNotNull(PubkyAuthClaim.fromWireValue("watch-only-account-v1.paykit-access-v1")),
    )

    private fun authUrl(capabilities: String, vararg claimValues: String): String {
        val claims = claimValues.joinToString(separator = "") {
            "&${PubkyAuthClaim.QUERY_PARAMETER}=$it"
        }
        return "pubkyauth://signin?caps=$capabilities&relay=https%3A%2F%2Fhttprelay.pubky.app%2Finbox%2F$claims"
    }

    private fun ringSignupUrl(signupToken: String? = null, scheme: String = "pubkyring"): String =
        "$scheme://signup?hs=homeserver" +
            "&relay=https%3A%2F%2Frelay.example%2Finbox%2F" +
            "&secret=secret&caps=%2Fpub%2Fexample.app%2F%3Arw" +
            signupToken?.let { "&st=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()

    private fun directSignupUrl(action: String, signupToken: String? = null): String =
        "pubkyauth://$action?hs=homeserver" +
            signupToken?.let { "&st=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
}
