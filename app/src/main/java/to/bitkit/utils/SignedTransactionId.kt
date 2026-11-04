package to.bitkit.utils

import to.bitkit.repositories.OnchainSendInput
import java.security.MessageDigest

/** Canonical Bitcoin txid of a consensus-encoded signed transaction, excluding witness data. */
internal object SignedTransactionId {
    private const val OUTPOINT_TXID_BYTES = 32
    private const val OUTPOINT_VOUT_BYTES = 4

    fun fromHex(hex: String): String = parse(hex).txid

    /** The coins a consensus-encoded signed transaction spends, as their txid and output index. */
    fun inputsFromHex(hex: String): List<OnchainSendInput> = parse(hex).inputs

    private class Parsed(val txid: String, val inputs: List<OnchainSendInput>)

    private fun parse(hex: String): Parsed {
        require(hex.length in 20..2_000_000 && hex.length % 2 == 0)
        val bytes = hex.hexToByteArray()
        var offset = 4
        fun skip(length: Int) {
            require(length >= 0 && length <= bytes.size - offset)
            offset += length
        }
        fun compactSize(): Int {
            require(offset < bytes.size)
            val prefix = bytes[offset++].toInt() and 255
            if (prefix < 253) return prefix
            val length = when (prefix) {
                253 -> 2
                254 -> 4
                else -> 8
            }
            require(length <= bytes.size - offset)
            var value = 0uL
            repeat(length) { value = value or ((bytes[offset++].toInt() and 255).toULong() shl (8 * it)) }
            require(value <= Int.MAX_VALUE.toULong())
            return value.toInt()
        }
        val hasWitness = bytes[offset] == 0.toByte()
        if (hasWitness) {
            require(bytes[offset + 1] == 1.toByte())
            skip(2)
        }
        val baseStart = offset
        val inputCount = compactSize()
        require(inputCount > 0 && inputCount <= bytes.size / 41)
        val inputs = List(inputCount) {
            val txidStart = offset
            skip(OUTPOINT_TXID_BYTES + OUTPOINT_VOUT_BYTES)
            val txid = bytes.copyOfRange(txidStart, txidStart + OUTPOINT_TXID_BYTES).reversedArray().toHexString()
            var vout = 0u
            repeat(OUTPOINT_VOUT_BYTES) {
                vout = vout or ((bytes[txidStart + OUTPOINT_TXID_BYTES + it].toInt() and 255).toUInt() shl (8 * it))
            }
            skip(compactSize())
            skip(4)
            OnchainSendInput(txid, vout)
        }
        val outputCount = compactSize()
        require(outputCount > 0 && outputCount <= bytes.size / 9)
        repeat(outputCount) {
            skip(8)
            skip(compactSize())
        }
        val baseEnd = offset
        if (hasWitness) {
            repeat(inputCount) {
                val itemCount = compactSize()
                require(itemCount <= bytes.size - offset)
                repeat(itemCount) { skip(compactSize()) }
            }
        }
        val lockTimeStart = offset
        skip(4)
        require(offset == bytes.size)
        val canonical = bytes.copyOfRange(0, 4) + bytes.copyOfRange(baseStart, baseEnd) +
            bytes.copyOfRange(lockTimeStart, offset)
        val sha256 = MessageDigest.getInstance("SHA-256")
        return Parsed(sha256.digest(sha256.digest(canonical)).reversedArray().toHexString(), inputs)
    }
}
