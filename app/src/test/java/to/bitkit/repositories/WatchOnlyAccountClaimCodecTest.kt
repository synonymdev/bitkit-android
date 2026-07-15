package to.bitkit.repositories

import org.bitcoinj.base.Base58
import org.junit.Test
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchOnlyAccountClaimCodecTest {
    @Test
    fun `unsigned claim contains exact account metadata`() {
        val rawXpub = ByteArray(WatchOnlyAccountClaimCodec.SERIALIZED_XPUB_LENGTH) { it.toByte() }
        val account = account(accountIndex = 42, xpub = base58CheckEncode(rawXpub))

        val payload = WatchOnlyAccountClaimCodec.encode(account)

        assertEquals(84, payload.size)
        assertEquals(WatchOnlyAccountClaimCodec.PAYLOAD_LENGTH, payload.size)
        assertEquals(WatchOnlyAccountClaimCodec.VERSION, payload[0])
        assertEquals(42, ByteBuffer.wrap(payload, 1, 4).int)
        assertEquals(WatchOnlyAccountClaimCodec.NATIVE_SEGWIT_ADDRESS_TYPE, payload[5])
        assertContentEquals(rawXpub, payload.copyOfRange(6, 84))
    }

    @Test
    fun `unsigned claim rejects invalid Base58Check checksum`() {
        val rawXpub = ByteArray(WatchOnlyAccountClaimCodec.SERIALIZED_XPUB_LENGTH) { it.toByte() }
        val validXpub = base58CheckEncode(rawXpub)
        val invalidXpub = validXpub.dropLast(1) + if (validXpub.last() == '1') '2' else '1'

        assertFailsWith<WatchOnlyAccountError.InvalidExtendedPublicKey> {
            WatchOnlyAccountClaimCodec.encode(account(accountIndex = 1, xpub = invalidXpub))
        }
    }

    private fun account(accountIndex: Int, xpub: String) = WatchOnlyAccountRecord(
        id = "id",
        walletIndex = 0,
        accountIndex = accountIndex,
        addressType = WatchOnlyAccountRepo.ADDRESS_TYPE_NATIVE_SEGWIT,
        xpub = xpub,
        requestFingerprint = "request",
        createdAt = 1,
        name = "Test",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.PendingDelivery,
    )

    private fun base58CheckEncode(payload: ByteArray): String {
        val checksum = sha256(sha256(payload)).copyOf(4)
        return Base58.encode(payload + checksum)
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
}
