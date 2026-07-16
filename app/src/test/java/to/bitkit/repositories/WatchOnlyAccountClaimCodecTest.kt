package to.bitkit.repositories

import org.junit.Test
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchOnlyAccountClaimCodecTest {
    @Test
    fun `unsigned claim contains exact account metadata`() {
        val rawXpub = TESTNET_SERIALIZED_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
        const val TESTNET_TPUB =
            "tpubDDWohsp5dx2iMJ9N7iHbgAEDhH4BJB9NWW1fEW3yA3AFNDREmpzteCXNqppMLUmKFY5q5e3" +
                "PXtS5CuqWCQbYcGhpPqYAgQSYdwknW9J6sQv"
        const val TESTNET_SERIALIZED_HEX =
            "043587cf03caafd489800000004b5fcc4a5fe210d9fba6616b4db1d025237dd7f035101f11f562401bc7104699" +
                "02e0bf22b51a6a49e0b149b995670d0ed9bb1fd99417748bacefba88fae655572d"
    }
}
