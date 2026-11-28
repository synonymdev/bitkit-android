package to.bitkit.wear.services

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The idempotency key of a Spark send of an invoice. Spark makes one transfer per key, so a retry under the same key
 * never pays the invoice twice: it gets back the transfer an earlier try committed, or sends when none did. A key
 * whose payment failed can't send again, so the next attempt takes the next key.
 *
 * Keys derive from the wallet seed, the payment hash and the attempt number, so a restored wallet derives the same
 * keys, no other wallet can, and the operators can't link a transfer to its invoice. iOS derives the same keys.
 *
 * A copy of the phone's `to.bitkit.services.SparkSendKey`: the watch and the phone share one wallet, so both must
 * derive the same key for an invoice, and Spark then pays it once even when both try. Keep the two in sync.
 */
object SparkSendKey {
    private val NAMESPACE = UUID.fromString("794acd96-39bc-4fd1-a09f-02b269a4a34d")
    private val LABEL = "bitkit spark send key".encodeToByteArray()
    private const val HMAC = "HmacSHA256"
    private const val UUID_BYTES = 16

    /** The secret all send keys derive from: an HMAC-SHA256 of a fixed label, keyed with the BIP39 [seed]. */
    fun secret(seed: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(seed, HMAC))
        doFinal(LABEL)
    }

    /**
     * The key of attempt [attempt] at paying the invoice with [paymentHash]: a UUIDv5 of [secret], the hash's 32 bytes
     * and [attempt] as 4 bytes big-endian.
     */
    fun key(secret: ByteArray, paymentHash: String, attempt: Int): String {
        require(attempt >= 0) { "Attempt '$attempt' is negative" }
        val name = secret + paymentHash.hexToByteArray() + ByteBuffer.allocate(Int.SIZE_BYTES)
            .putInt(attempt)
            .array()
        val namespace = ByteBuffer.allocate(UUID_BYTES)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        val hash = MessageDigest.getInstance("SHA-1").run {
            update(namespace)
            digest(name)
        }
        hash[6] = (hash[6].toInt() and 0x0f or 0x50).toByte()
        hash[8] = (hash[8].toInt() and 0x3f or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash, 0, UUID_BYTES)
        return UUID(bytes.long, bytes.long).toString()
    }
}
