package to.bitkit.repositories

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Test
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchOnlyAccountClaimCodecTest {
    @Test
    fun `signed claim contains account metadata and request-bound signature`() {
        val rawXpub = ByteArray(WatchOnlyAccountClaimCodec.SERIALIZED_XPUB_LENGTH) { it.toByte() }
        val privateKeyBytes = ByteArray(32) { 7 }
        val authUrl = "pubkyauth://signin?secret=request-secret"
        val account = account(accountIndex = 42, xpub = base58CheckEncode(rawXpub))

        val payload = WatchOnlyAccountClaimCodec.encode(account, authUrl, privateKeyBytes)

        assertEquals(WatchOnlyAccountClaimCodec.PAYLOAD_LENGTH, payload.size)
        assertEquals(WatchOnlyAccountClaimCodec.VERSION, payload[0])
        assertEquals(42, ByteBuffer.wrap(payload, 1, 4).int)
        assertEquals(WatchOnlyAccountClaimCodec.NATIVE_SEGWIT_ADDRESS_TYPE, payload[5])
        assertContentEquals(rawXpub, payload.copyOfRange(6, 84))

        val unsignedClaim = payload.copyOfRange(0, 84)
        val signature = payload.copyOfRange(84, payload.size)
        val signable = "x-bitkit-claim|watch-only-account-v1|".encodeToByteArray() +
            WatchOnlyAccountClaimCodec.requestSecretHash(authUrl) +
            unsignedClaim
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PrivateKeyParameters(privateKeyBytes, 0).generatePublicKey())
            update(signable, 0, signable.size)
        }
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun `request secret hashing percent decodes without treating plus as a space`() {
        val encoded = WatchOnlyAccountClaimCodec.requestSecretHash(
            "pubkyauth://signin?secret=one%2Ftwo+three",
        )

        assertContentEquals(sha256("one/two+three".encodeToByteArray()), encoded)
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
        val source = payload + checksum
        var number = BigInteger(1, source)
        val output = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (number > BigInteger.ZERO) {
            val division = number.divideAndRemainder(base)
            output.append(BASE58_ALPHABET[division[1].toInt()])
            number = division[0]
        }
        repeat(source.takeWhile { it == 0.toByte() }.size) { output.append('1') }
        return output.reverse().toString()
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

    companion object {
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}
