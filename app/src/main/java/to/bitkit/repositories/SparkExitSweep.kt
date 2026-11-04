package to.bitkit.repositories

import to.bitkit.models.safe
import to.bitkit.utils.AppError
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Moves the fee funding an exit left at its funding address to savings once the exit is done: change from a branch
 * whose refund the operators published, or funding a rebuild no longer needed. The SDK's single-key CPFP signer signs
 * the P2WPKH inputs of the PSBT built here and finalizes them, so the funding key never leaves the signer.
 */
object SparkExitSweep {
    private const val SEQUENCE_RBF = 0xfffffffdL

    /** Bitcoin Core's spend sizes for its dust rule: a witness program's input, and any other. */
    private const val WITNESS_SPEND_BYTES = 67uL
    private const val LEGACY_SPEND_BYTES = 148uL

    data class Input(val txid: String, val vout: UInt, val valueSats: ULong)

    /** An unsigned sweep of [inputs] to [destination], or null when what it pays would be under the dust limit. */
    class Plan(val inputs: List<Input>, val destinationScript: ByteArray, val amountSats: ULong, val feeSats: ULong)

    fun plan(inputs: List<Input>, destination: String, feeRateSatPerVbyte: ULong): Plan? {
        if (inputs.isEmpty()) return null
        val script = AddressScript.of(destination)
        val fee = vsize(inputs.size, script.size) * maxOf(1uL, feeRateSatPerVbyte)
        val total = inputs.fold(0uL) { sum, input -> sum.safe() + input.valueSats.safe() }
        if (total < fee.safe() + dustLimit(script).safe()) return null
        return Plan(inputs, script, total - fee, fee)
    }

    /** P2WPKH inputs sized for 72-byte signatures and one output: ceil((4 · base + witness) / 4). */
    fun vsize(inputCount: Int, outputScriptSize: Int): ULong {
        val n = inputCount.toULong()
        val base = 10uL + 41uL * n + 9uL + outputScriptSize.toULong()
        val witness = 2uL + 108uL * n
        return (4uL * base + witness + 3uL) / 4uL
    }

    /** Bitcoin Core's dust limit of an output: 3 · (output size + spend size), 294 for P2WPKH, 330 for P2TR. */
    fun dustLimit(script: ByteArray): ULong {
        val isWitness = script.size >= 4 && (script[0] == 0.toByte() || script[0] in 0x51.toByte()..0x60.toByte())
        val spend = if (isWitness) WITNESS_SPEND_BYTES else LEGACY_SPEND_BYTES
        return 3uL * (9uL + script.size.toULong() + spend)
    }

    /** A BIP 174 PSBT of [plan], each input carrying its P2WPKH `witness_utxo` at [fundingAddress] for the signer. */
    fun psbt(plan: Plan, fundingAddress: String): ByteArray {
        val fundingScript = AddressScript.of(fundingAddress)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        out.writeKeyValue(byteArrayOf(0x00), unsignedTx(plan))
        out.write(0x00)
        plan.inputs.forEach {
            out.writeKeyValue(byteArrayOf(0x01), le64(it.valueSats.toLong()) + varBytes(fundingScript))
            out.write(0x00)
        }
        out.write(0x00)
        return out.toByteArray()
    }

    /** The signed transaction hex from a PSBT whose every input the signer finalized. */
    fun signedTxHex(plan: Plan, signedPsbt: ByteArray): String {
        val witnesses = PsbtReader(signedPsbt).finalWitnesses(plan.inputs.size)
        val out = ByteArrayOutputStream()
        out.write(le32(2))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(txBody(plan))
        witnesses.forEach { out.write(it) }
        out.write(le32(0))
        return out.toByteArray().toHexString()
    }

    private fun unsignedTx(plan: Plan): ByteArray = le32(2) + txBody(plan) + le32(0)

    private fun txBody(plan: Plan): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(compactSize(plan.inputs.size.toLong()))
        plan.inputs.forEach {
            out.write(it.txid.hexToByteArray().reversedArray())
            out.write(le32(it.vout.toLong()))
            out.write(0x00)
            out.write(le32(SEQUENCE_RBF))
        }
        out.write(compactSize(1))
        out.write(le64(plan.amountSats.toLong()))
        out.write(varBytes(plan.destinationScript))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeKeyValue(key: ByteArray, value: ByteArray) {
        write(varBytes(key))
        write(varBytes(value))
    }

    private fun varBytes(bytes: ByteArray) = compactSize(bytes.size.toLong()) + bytes

    private fun compactSize(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) + le(n, 2)
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le(n, 4)
        else -> byteArrayOf(0xff.toByte()) + le(n, 8)
    }

    private fun le32(n: Long) = le(n, 4)
    private fun le64(n: Long) = le(n, 8)
    private fun le(n: Long, size: Int) = ByteArray(size) { ((n ushr (8 * it)) and 0xff).toByte() }

    /** Reads the `final_scriptwitness` of each input, already serialized as the transaction carries it. */
    private class PsbtReader(private val bytes: ByteArray) {
        private var offset = 5

        fun finalWitnesses(inputCount: Int): List<ByteArray> {
            if (!bytes.copyOfRange(0, 5).contentEquals(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))) {
                throw SparkExitSweepError("Not a PSBT")
            }
            skipMap()
            return List(inputCount) { index ->
                var witness: ByteArray? = null
                while (true) {
                    val key = readVarBytes()
                    if (key.isEmpty()) break
                    val value = readVarBytes()
                    if (key[0] == FINAL_SCRIPTWITNESS) witness = value
                }
                witness ?: throw SparkExitSweepError("Input '$index' was not signed")
            }
        }

        private fun skipMap() {
            while (true) {
                if (readVarBytes().isEmpty()) return
                readVarBytes()
            }
        }

        private fun readVarBytes(): ByteArray {
            val length = readCompactSize()
            if (length < 0 || length > bytes.size - offset) throw SparkExitSweepError("Truncated PSBT")
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        private fun readCompactSize(): Int {
            if (offset >= bytes.size) throw SparkExitSweepError("Truncated PSBT")
            val prefix = bytes[offset++].toInt() and 0xff
            val size = when (prefix) {
                0xfd -> 2
                0xfe -> 4
                0xff -> 8
                else -> return prefix
            }
            if (size > bytes.size - offset) throw SparkExitSweepError("Truncated PSBT")
            var value = 0L
            repeat(size) { value = value or ((bytes[offset++].toLong() and 0xff) shl (8 * it)) }
            if (value > Int.MAX_VALUE) throw SparkExitSweepError("PSBT field too large")
            return value.toInt()
        }

        private companion object {
            const val FINAL_SCRIPTWITNESS: Byte = 0x08
        }
    }
}

class SparkExitSweepError(message: String) : AppError(message)

/** An address to the output script it pays: segwit (BIP 173 and 350), P2PKH or P2SH. */
object AddressScript {
    private val P2PKH_VERSIONS = setOf(0x00, 0x6f)
    private val P2SH_VERSIONS = setOf(0x05, 0xc4)
    private const val HASH_LENGTH = 20
    private const val CHECKSUM_LENGTH = 4

    fun of(address: String): ByteArray {
        val hrp = address.lowercase().substringBeforeLast('1', "")
        if (hrp in setOf("bc", "tb", "bcrt")) return Bech32.scriptPubKey(address)
        val decoded = SparkExitFundingKey.base58Decode(address)
            ?.takeIf { it.size == 1 + HASH_LENGTH + CHECKSUM_LENGTH }
            ?: throw SparkExitSweepError("Invalid address '$address'")
        val payload = decoded.copyOfRange(0, 1 + HASH_LENGTH)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val checksum = sha256.digest(sha256.digest(payload)).copyOfRange(0, CHECKSUM_LENGTH)
        if (!checksum.contentEquals(decoded.copyOfRange(1 + HASH_LENGTH, decoded.size))) {
            throw SparkExitSweepError("Invalid checksum in '$address'")
        }
        val hash = payload.copyOfRange(1, payload.size)
        return when (payload[0].toInt() and 0xff) {
            in P2PKH_VERSIONS -> {
                byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
            }
            in P2SH_VERSIONS -> byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> throw SparkExitSweepError("Unsupported address '$address'")
        }
    }
}

/** Segwit addresses (BIP 173 and BIP 350) to the output script they pay. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3
    private const val MIN_PROGRAM = 2
    private const val MAX_PROGRAM = 40

    fun scriptPubKey(address: String): ByteArray {
        val lower = address.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 7 > lower.length) throw SparkExitSweepError("Invalid address '$address'")
        val hrp = lower.substring(0, separator)
        val data = lower.substring(separator + 1).map {
            CHARSET.indexOf(it).takeIf { i -> i >= 0 } ?: throw SparkExitSweepError("Invalid address '$address'")
        }
        val version = data.first()
        val checksum = polymod(expand(hrp) + data)
        val expected = if (version == 0) BECH32_CONST else BECH32M_CONST
        if (checksum != expected) throw SparkExitSweepError("Invalid checksum in '$address'")
        val program = convertBits(data.subList(1, data.size - 6))
        if (program.size !in MIN_PROGRAM..MAX_PROGRAM || (version == 0 && program.size != 20 && program.size != 32)) {
            throw SparkExitSweepError("Invalid program in '$address'")
        }
        val opVersion = if (version == 0) 0 else 0x50 + version
        return byteArrayOf(opVersion.toByte(), program.size.toByte()) + program
    }

    private fun expand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        values.forEach { value ->
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            generators.forEachIndexed { i, g -> if ((top ushr i) and 1 == 1) chk = chk xor g }
        }
        return chk
    }

    private fun convertBits(data: List<Int>): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        data.forEach {
            acc = (acc shl 5) or it
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xff)
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) throw SparkExitSweepError("Invalid padding")
        return out.toByteArray()
    }
}
