package to.bitkit.repositories

import org.junit.Test
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkExitSweepTest {
    private val funding = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
    private val savings = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"

    @Test
    fun `segwit addresses become the script they pay`() {
        assertEquals(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            Bech32.scriptPubKey("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4").toHexString(),
        )
        assertEquals(
            "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Bech32.scriptPubKey(savings).toHexString(),
        )
        assertFailsWith<SparkExitSweepError> { Bech32.scriptPubKey("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5") }
    }

    @Test
    fun `a sweep pays every input less its fee, and none that would leave dust`() {
        val inputs = listOf(SparkExitSweep.Input("aa".repeat(32), 1u, 5_035uL))

        val plan = assertNotNull(SparkExitSweep.plan(inputs, savings, feeRateSatPerVbyte = 2uL))

        // 11 overhead + 68 for the input + 9 + 34 for the P2TR output, at 2 sat/vB.
        assertEquals(244uL, plan.feeSats)
        assertEquals(4_791uL, plan.amountSats)
        assertNull(SparkExitSweep.plan(listOf(inputs.first().copy(valueSats = 700uL)), savings, 2uL))
        assertNull(SparkExitSweep.plan(emptyList(), savings, 2uL))
    }

    @Test
    fun `the signed transaction carries the signer's witness and keeps the unsigned txid`() {
        val plan = assertNotNull(
            SparkExitSweep.plan(
                listOf(
                    SparkExitSweep.Input("11".repeat(32), 0u, 3_000uL),
                    SparkExitSweep.Input("22".repeat(32), 2u, 2_035uL),
                ),
                savings,
                1uL,
            ),
        )
        val psbt = SparkExitSweep.psbt(plan, funding)
        // A P2WPKH witness: two items, a 72-byte signature with its sighash flag and a 33-byte key.
        val witness = "02" + "48" + "30".repeat(71) + "01" + "21" + "02".repeat(33)

        val hex = SparkExitSweep.signedTxHex(plan, signLikeTheSdk(psbt, witness, inputCount = 2))

        assertTrue(hex.startsWith("02000000" + "0001" + "02" + "11".repeat(32) + "00000000" + "00" + "fdffffff"))
        assertTrue(hex.endsWith(witness + witness + "00000000"))
        val unsigned = "02000000" + hex.substring(12, hex.indexOf(witness)) + "00000000"
        assertEquals(SignedTransactionId.fromHex(unsigned), SignedTransactionId.fromHex(hex))
    }

    @Test
    fun `an input the signer left unsigned is refused`() {
        val inputs = listOf(SparkExitSweep.Input("11".repeat(32), 0u, 3_000uL))
        val plan = assertNotNull(SparkExitSweep.plan(inputs, savings, 1uL))

        assertFailsWith<SparkExitSweepError> { SparkExitSweep.signedTxHex(plan, SparkExitSweep.psbt(plan, funding)) }
    }

    /** Adds a `final_scriptwitness` to each input map, as the SDK's single-key signer does. */
    private fun signLikeTheSdk(psbt: ByteArray, witness: String, inputCount: Int): ByteArray {
        var offset = 5
        fun readCompactSize(): Int {
            val prefix = psbt[offset++].toInt() and 0xff
            if (prefix < 0xfd) return prefix
            check(prefix == 0xfd)
            return ((psbt[offset++].toInt() and 0xff) or ((psbt[offset++].toInt() and 0xff) shl 8))
        }
        fun copyMap(extra: ByteArray): ByteArray {
            val start = offset
            while (true) {
                val keyLength = readCompactSize()
                if (keyLength == 0) return psbt.copyOfRange(start, offset - 1) + extra + byteArrayOf(0x00)
                offset += keyLength
                val valueLength = readCompactSize()
                offset += valueLength
            }
        }
        val witnessBytes = witness.hexToByteArray()
        val finalWitness = byteArrayOf(0x01, 0x08, witnessBytes.size.toByte()) + witnessBytes
        var signed = psbt.copyOfRange(0, 5) + copyMap(ByteArray(0))
        repeat(inputCount) { signed += copyMap(finalWitness) }
        return signed + psbt.copyOfRange(offset, psbt.size)
    }
}
