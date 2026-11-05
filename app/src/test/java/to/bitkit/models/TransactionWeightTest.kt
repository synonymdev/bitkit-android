package to.bitkit.models

import org.junit.Test
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxInput
import org.lightningdevkit.ldknode.TxOutput
import kotlin.test.assertEquals

class TransactionWeightTest {
    private companion object {
        const val P2WPKH_SCRIPT = "0014" + "11111111111111111111111111111111111111ab"
        const val P2PKH_SCRIPT = "76a914" + "33333333333333333333333333333333333333ab" + "88ac"
    }

    @Test
    fun `weight of a native segwit spend counts its witness at a quarter`() {
        val tx = TransactionDetails(
            amountSats = 0,
            inputs = listOf(witnessInput()),
            outputs = listOf(output(P2WPKH_SCRIPT, 0u), output(P2WPKH_SCRIPT, 1u)),
        )

        // 113 stripped bytes and 110 witness bytes: the 141 vB of a 1-in 2-out P2WPKH transaction
        assertEquals(562uL, tx.weight())
    }

    @Test
    fun `fee rate is per virtual byte and rounds up`() {
        val tx = TransactionDetails(
            amountSats = 0,
            inputs = listOf(witnessInput()),
            outputs = listOf(output(P2WPKH_SCRIPT, 0u), output(P2WPKH_SCRIPT, 1u)),
        )

        // 562 WU is 141 vB
        assertEquals(2uL, tx.feeRate(282uL))
        assertEquals(3uL, tx.feeRate(283uL))
    }

    @Test
    fun `weight of a legacy spend has no witness section`() {
        val tx = TransactionDetails(
            amountSats = 0,
            inputs = listOf(legacyInput()),
            outputs = listOf(output(P2PKH_SCRIPT, 0u)),
        )

        assertEquals(768uL, tx.weight())
    }

    @Test
    fun `weight of a mixed spend gives the legacy input an empty witness`() {
        val tx = TransactionDetails(
            amountSats = 0,
            inputs = listOf(legacyInput(), witnessInput()),
            outputs = listOf(output(P2WPKH_SCRIPT, 0u)),
        )

        assertEquals(1_031uL, tx.weight())
    }

    private fun witnessInput() = TxInput(
        txid = "parent",
        vout = 0u,
        scriptsig = "",
        witness = listOf("30".repeat(72), "02".repeat(33)),
        sequence = 0xfffffffdu,
    )

    private fun legacyInput() = TxInput(
        txid = "parent",
        vout = 1u,
        scriptsig = "48" + "30".repeat(72) + "21" + "02".repeat(33),
        witness = emptyList(),
        sequence = 0xfffffffdu,
    )

    private fun output(script: String, n: UInt) = TxOutput(
        scriptpubkey = script,
        scriptpubkeyType = if (script == P2WPKH_SCRIPT) "p2wpkh" else "p2pkh",
        scriptpubkeyAddress = null,
        value = 10_000,
        n = n,
    )
}
