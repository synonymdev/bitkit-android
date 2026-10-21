package to.bitkit.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import to.bitkit.ext.fromHex
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthClaim.Item
import to.bitkit.models.PubkyAuthClaimCodec
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchOnlyAccountClaimCodecTest {
    @Test
    fun `combined companion claim matches the canonical server fixture`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/bitkit-combined-claim-v1.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val claimType = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1)
        val accountPayload = WatchOnlyAccountClaimCodec.encode(
            account(fixture.getValue("account_index").jsonPrimitive.int, TESTNET_TPUB),
        ) { fixture.getValue("serialized_xpub_hex").jsonPrimitive.content.fromHex() }
        val payload = PubkyAuthClaimCodec.encode(
            claim = claimType,
            accountPayload = accountPayload,
            generation = fixture.getValue("key_generation").jsonPrimitive.content.toULong(),
            secret = fixture.getValue("paykit_secret_hex").jsonPrimitive.content.fromHex(),
        )

        assertEquals(fixture.getValue("query_parameter").jsonPrimitive.content, PubkyAuthClaim.QUERY_PARAMETER)
        assertEquals(fixture.getValue("claim_type").jsonPrimitive.content, claimType.wireValue)
        assertEquals(
            fixture.getValue("capabilities").jsonPrimitive.content,
            PubkyAuthClaim.REQUIRED_CAPABILITIES,
        )
        assertEquals(124, payload.size)
        assertContentEquals(fixture.getValue("unsigned_payload_hex").jsonPrimitive.content.fromHex(), payload)
        assertContentEquals(
            payload,
            PubkyAuthClaimCodec.encode(
                claim = requireNotNull(PubkyAuthClaim.fromWireValue("watch-only-account-v1.paykit-access-v1")),
                accountPayload = accountPayload,
                generation = fixture.getValue("key_generation").jsonPrimitive.content.toULong(),
                secret = fixture.getValue("paykit_secret_hex").jsonPrimitive.content.fromHex(),
            ),
        )
    }

    @Test
    fun `companion claims match shared fixed bytes`() {
        val accountPayload = WatchOnlyAccountClaimCodec.encode(account(42, TESTNET_TPUB)) {
            TESTNET_SERIALIZED_HEX.fromHex()
        }
        val fixtures = listOf(
            PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1) to WATCH_ONLY_HEX,
            PubkyAuthClaim(Item.PAYKIT_ACCESS_V1) to PAYKIT_HEX,
            PubkyAuthClaim(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1) to COMBINED_HEX,
            requireNotNull(PubkyAuthClaim.fromWireValue("watch-only-account-v1.paykit-access-v1")) to COMBINED_HEX,
        )
        for ((claim, expected) in fixtures) {
            val payload = PubkyAuthClaimCodec.encode(
                claim = claim,
                accountPayload = if (claim.includesWatchOnlyAccount) accountPayload else byteArrayOf(),
                generation = if (claim.includesPaykitAccess) 3uL else null,
                secret = if (claim.includesPaykitAccess) ByteArray(32) { 7 } else null,
            )
            assertContentEquals(expected.fromHex(), payload, claim.wireValue)
        }
    }

    @Test
    fun `companion claims reject mismatched account or key payloads`() {
        val accountPayload = WATCH_ONLY_HEX.fromHex()
        val key = ByteArray(32) { 7 }
        assertFailsWith<IllegalArgumentException> {
            PubkyAuthClaimCodec.encode(PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1), accountPayload, 3uL, key)
        }
        for (payload in listOf(byteArrayOf(), COMBINED_HEX.fromHex(), ByteArray(84))) {
            assertFailsWith<IllegalArgumentException> {
                PubkyAuthClaimCodec.encode(PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1), payload)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PubkyAuthClaimCodec.encode(PubkyAuthClaim(Item.PAYKIT_ACCESS_V1), accountPayload, 3uL, key)
        }
        for (generation in listOf(null, 0uL)) {
            assertFailsWith<IllegalArgumentException> {
                PubkyAuthClaimCodec.encode(PubkyAuthClaim(Item.PAYKIT_ACCESS_V1), byteArrayOf(), generation, key)
            }
        }
        for (secret in listOf(null, ByteArray(31), ByteArray(33))) {
            assertFailsWith<IllegalArgumentException> {
                PubkyAuthClaimCodec.encode(PubkyAuthClaim(Item.PAYKIT_ACCESS_V1), byteArrayOf(), 3uL, secret)
            }
        }
    }

    @Test
    fun `unsigned claim contains exact account metadata`() {
        val rawXpub = TESTNET_SERIALIZED_HEX.fromHex()
        val account = account(accountIndex = 42, xpub = TESTNET_TPUB)

        val payload = WatchOnlyAccountClaimCodec.encode(account) { xpub ->
            require(xpub == TESTNET_TPUB)
            rawXpub
        }

        assertEquals(84, payload.size)
        assertEquals(WatchOnlyAccountClaimCodec.PAYLOAD_LENGTH, payload.size)
        assertEquals(WatchOnlyAccountClaimCodec.VERSION, payload[0])
        assertEquals(42, ByteBuffer.wrap(payload, 1, 4).int)
        assertEquals(WatchOnlyAccountClaimCodec.NATIVE_SEGWIT_ADDRESS_TYPE, payload[5])
        assertContentEquals(rawXpub, payload.copyOfRange(6, 84))
    }

    @Test
    fun `unsigned claim rejects invalid Base58Check checksum`() {
        val invalidXpub = TESTNET_TPUB.dropLast(1) + if (TESTNET_TPUB.last() == '1') '2' else '1'

        assertFailsWith<WatchOnlyAccountError.InvalidExtendedPublicKey> {
            WatchOnlyAccountClaimCodec.encode(account(accountIndex = 1, xpub = invalidXpub)) {
                throw IllegalArgumentException("Invalid extended public key")
            }
        }
    }

    private fun account(accountIndex: Int, xpub: String) = WatchOnlyAccountRecord(
        id = "id",
        walletIndex = 0,
        accountIndex = accountIndex,
        addressType = WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE,
        xpub = xpub,
        requestFingerprint = "request",
        createdAt = 1,
        name = "Test",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.PendingDelivery,
    )

    private companion object {
        const val WATCH_ONLY_HEX =
            "010000002a00043587cf03caafd489800000004b5fcc4a5fe210d9fba6616b4db1d025237dd7f035101f11f562401bc7104699" +
                "02e0bf22b51a6a49e0b149b995670d0ed9bb1fd99417748bacefba88fae655572d"
        const val PAYKIT_HEX =
            "0100000000000000030707070707070707070707070707070707070707070707070707070707070707"
        const val COMBINED_HEX =
            "010000002a00043587cf03caafd489800000004b5fcc4a5fe210d9fba6616b4db1d025237dd7f035101f11f562401bc7104699" +
                "02e0bf22b51a6a49e0b149b995670d0ed9bb1fd99417748bacefba88fae655572d" +
                "00000000000000030707070707070707070707070707070707070707070707070707070707070707"
        const val TESTNET_TPUB =
            "tpubDDWohsp5dx2iMJ9N7iHbgAEDhH4BJB9NWW1fEW3yA3AFNDREmpzteCXNqppMLUmKFY5q5e3" +
                "PXtS5CuqWCQbYcGhpPqYAgQSYdwknW9J6sQv"
        const val TESTNET_SERIALIZED_HEX =
            "043587cf03caafd489800000004b5fcc4a5fe210d9fba6616b4db1d025237dd7f035101f11f562401bc7104699" +
                "02e0bf22b51a6a49e0b149b995670d0ed9bb1fd99417748bacefba88fae655572d"
    }
}
