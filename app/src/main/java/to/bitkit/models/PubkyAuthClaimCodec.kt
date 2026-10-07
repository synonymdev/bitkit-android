package to.bitkit.models

import java.nio.ByteBuffer

object PubkyAuthClaimCodec {
    /** Size of the versioned account metadata and serialized extended public key. */
    const val WATCH_ONLY_PAYLOAD_LENGTH = 84

    /** Size of the version, generation, and Paykit identity secret. */
    const val PAYKIT_PAYLOAD_LENGTH = 41

    /** Size of account metadata followed by the generation and Paykit identity secret. */
    const val COMBINED_PAYLOAD_LENGTH = 124

    fun validateAccountPayload(claim: PubkyAuthClaim, accountPayload: ByteArray) {
        if (claim.sharesUsdt) {
            require(!claim.includesPaykitAccess && accountPayload.isNotEmpty()) { "Invalid payment-details claim" }
            return
        }
        if (!claim.includesWatchOnlyAccount) {
            require(accountPayload.isEmpty()) { "Paykit-only approval cannot include an account" }
            return
        }
        require(accountPayload.size == WATCH_ONLY_PAYLOAD_LENGTH) { "Invalid watch-only payload length" }
        require(accountPayload[0] == 1.toByte() && accountPayload[5] == 0.toByte()) {
            "Unsupported watch-only payload version or address type"
        }
    }

    fun encode(
        claim: PubkyAuthClaim,
        accountPayload: ByteArray,
        generation: ULong? = null,
        secret: ByteArray? = null,
    ): ByteArray {
        validateAccountPayload(claim, accountPayload)
        if (!claim.includesPaykitAccess) {
            require(generation == null && secret == null) { "Watch-only approval cannot include a Paykit key" }
            return accountPayload.copyOf()
        }
        require(generation != null && generation > 0uL) { "Invalid Paykit key generation" }
        require(secret?.size == 32) { "Invalid Paykit identity secret length" }
        val prefix = if (claim.includesWatchOnlyAccount) accountPayload else byteArrayOf(1)
        val length = if (claim.includesWatchOnlyAccount) COMBINED_PAYLOAD_LENGTH else PAYKIT_PAYLOAD_LENGTH
        return ByteBuffer.allocate(length)
            .put(prefix)
            .putLong(generation.toLong())
            .put(secret)
            .array()
    }
}
