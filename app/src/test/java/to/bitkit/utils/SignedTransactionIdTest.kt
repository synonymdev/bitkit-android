package to.bitkit.utils

import org.junit.Test
import to.bitkit.repositories.OnchainSendInput
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedTransactionIdTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction.hex"))
        .bufferedReader().readText().trim()

    @Test
    fun `matches independently observed backend transaction and ignores witness bytes`() {
        val original = fixture()
        val expected = "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e"
        assertEquals(expected, SignedTransactionId.fromHex(original))
        val withoutWitness = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction-legacy.hex"))
            .bufferedReader().readText().trim()
        assertEquals(expected, SignedTransactionId.fromHex(withoutWitness))
        val witnessChanged = original.hexToByteArray().also {
            it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte()
        }
        assertEquals(expected, SignedTransactionId.fromHex(witnessChanged.toHexString()))
    }

    @Test
    fun `reads the coins a signed transaction spends with or without witness data`() {
        val expected = listOf(
            OnchainSendInput("167c8b8b6169007fd94edc121d606c829cbb5d6b51075bb0c664911848a0c5f7", 1u),
        )
        assertEquals(expected, SignedTransactionId.inputsFromHex(fixture()))
        val withoutWitness = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction-legacy.hex"))
            .bufferedReader().readText().trim()
        assertEquals(expected, SignedTransactionId.inputsFromHex(withoutWitness))
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.inputsFromHex(fixture().dropLast(2)) }
    }

    @Test
    fun `rejects truncated or trailing transaction data before persistence`() {
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex(fixture().dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex(fixture() + "00") }
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex("not-a-transaction") }
    }
}
