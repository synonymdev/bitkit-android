package to.bitkit.repositories

import org.junit.Test
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkExitSweepTest : BaseUnitTest() {
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
        assertEquals(
            "76a91477bff20c60e522dfaa3350c39b030a5d004e839a88ac",
            AddressScript.of("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2").toHexString(),
        )
        assertEquals(
            "a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87",
            AddressScript.of("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy").toHexString(),
        )
        assertFailsWith<SparkExitSweepError> { AddressScript.of("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3") }
    }

    @Test
    fun `dust limits follow bitcoin core per output type`() {
        assertEquals(294uL, SparkExitSweep.dustLimit(AddressScript.of("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")))
        assertEquals(330uL, SparkExitSweep.dustLimit(AddressScript.of(savings)))
        assertEquals(540uL, SparkExitSweep.dustLimit(AddressScript.of("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")))
        assertEquals(546uL, SparkExitSweep.dustLimit(AddressScript.of("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")))
    }

    @Test
    fun `a sweep pays every input less its fee, and none that would leave dust`() {
        val inputs = listOf(SparkExitSweep.Input("aa".repeat(32), 1u, 5_035uL))

        val plan = assertNotNull(SparkExitSweep.plan(inputs, savings, feeRateSatPerVbyte = 2uL))

        // 11 overhead + 68 for the input + 9 + 34 for the P2TR output, at 2 sat/vB.
        assertEquals(244uL, plan.feeSats)
        assertEquals(4_791uL, plan.amountSats)
        // 500 less 244 of fee is under P2TR's dust limit of 330.
        assertNull(SparkExitSweep.plan(listOf(inputs.first().copy(valueSats = 500uL)), savings, 2uL))
        assertNull(SparkExitSweep.plan(emptyList(), savings, 2uL))
    }

    @Test
    fun `the signed transaction carries the signer's witness and keeps the unsigned txid`() = test {
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
        val witness = StubCpfpSigner.P2WPKH_WITNESS

        val hex = SparkExitSweep.signedTxHex(plan, StubCpfpSigner().signPsbt(psbt))

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
}
