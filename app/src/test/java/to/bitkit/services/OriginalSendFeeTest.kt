package to.bitkit.services

import org.junit.Test
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxInput
import org.lightningdevkit.ldknode.TxOutput
import to.bitkit.repositories.OnchainSendInput
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OriginalSendFeeTest {
    private val parentId = "ef".repeat(32)
    private val input = TxInput(parentId, 1u, "", emptyList(), UInt.MAX_VALUE - 2u)
    private fun output(value: Long, n: UInt = 0u) = TxOutput("", null, null, value, n)
    private val inputs = listOf(OnchainSendInput(parentId, 1u))
    private val details = TransactionDetails(-1000L, listOf(input), listOf(output(1000), output(18719, 1u)))
    private val parent = TransactionDetails(20000L, emptyList(), listOf(output(20000, 1u)))

    @Test
    fun `winning fee uses exact previous output and never original fee hint`() {
        assertEquals(281uL, exactOriginalSendFee(details, inputs) { parent })
        assertNull(exactOriginalSendFee(details, inputs) { null })
    }

    @Test
    fun `invalid or substituted input and previous output cannot complete a fee`() {
        assertFailsWith<IllegalStateException> { exactOriginalSendFee(details, emptyList()) { parent } }
        assertFailsWith<IllegalStateException> {
            exactOriginalSendFee(details.copy(inputs = listOf(input, input)), inputs) { parent }
        }
        assertFailsWith<NoSuchElementException> {
            exactOriginalSendFee(details, inputs) { parent.copy(outputs = listOf(output(20000, 0u))) }
        }
        assertFailsWith<IllegalStateException> {
            exactOriginalSendFee(details, inputs) { parent.copy(outputs = listOf(output(-1, 1u))) }
        }
        assertFailsWith<IllegalStateException> {
            exactOriginalSendFee(details, inputs) { parent.copy(outputs = listOf(output(100, 1u))) }
        }
    }

    @Test
    fun `negative and overflowing totals cannot complete a fee`() {
        assertFailsWith<IllegalStateException> {
            exactOriginalSendFee(details.copy(outputs = listOf(output(-1))), inputs) { parent }
        }
        val other = input.copy(vout = 2u)
        val three = input.copy(vout = 3u)
        val many = details.copy(inputs = listOf(input, other, three))
        val original = many.inputs.map { OnchainSendInput(it.txid, it.vout) }
        assertFailsWith<IllegalStateException> {
            exactOriginalSendFee(many, original) {
                parent.copy(outputs = listOf(output(Long.MAX_VALUE, 1u), output(Long.MAX_VALUE, 2u), output(Long.MAX_VALUE, 3u)))
            }
        }
    }
}
