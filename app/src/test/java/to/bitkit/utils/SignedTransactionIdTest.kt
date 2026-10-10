package to.bitkit.utils

import org.junit.Test
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
        val witnessChanged = original.hexToByteArray().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertEquals(expected, SignedTransactionId.fromHex(witnessChanged.toHexString()))
    }

    @Test
    fun `rejects truncated or trailing transaction data before persistence`() {
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex(fixture().dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex(fixture() + "00") }
        assertFailsWith<IllegalArgumentException> { SignedTransactionId.fromHex("not-a-transaction") }
    }
}
