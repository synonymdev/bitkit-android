package to.bitkit.services

import org.junit.Test
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxInput
import org.lightningdevkit.ldknode.TxOutput
import to.bitkit.repositories.OnchainPreparedReceipt
import to.bitkit.repositories.OnchainSendAttempt
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
    fun `a bump that added a coin reads its fee from every input`() {
        val addedId = "ab".repeat(32)
        val added = TxInput(addedId, 0u, "", emptyList(), UInt.MAX_VALUE - 2u)
        val bump = details.copy(inputs = listOf(input, added), outputs = listOf(output(1000), output(23_000, 1u)))
        val addedParent = TransactionDetails(5000L, emptyList(), listOf(output(5_000)))
        val parents = mapOf(parentId to parent, addedId to addedParent)

        assertEquals(1_000uL, exactTransactionFee(bump) { parents[it] })
        // The original's own inputs no longer match, so it is not read as the original send's fee
        assertFailsWith<IllegalStateException> { exactOriginalSendFee(bump, inputs) { parents[it] } }
        assertNull(exactTransactionFee(bump) { if (it == parentId) parent else null })
    }

    @Test
    fun `a lost candidate is read only from exactly the original coins paying the full amount`() {
        val attempt = OnchainSendAttempt(
            walletId = "wallet", attemptId = "attempt", requestId = null, orderId = null, address = "bcrt1qrecipient",
            amountSats = 1_000uL, isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = false,
            channelId = null, tags = emptyList(), originalInputs = inputs,
        )
        fun paid(value: Long, address: String = attempt.address) = TxOutput("", null, address, value, 0u)
        val payment = details.copy(outputs = listOf(paid(1000), output(18719, 1u)))

        val receipt = lostCandidateReceipt("CD".repeat(32), payment, attempt) { parent }

        // 281 sats over the 69 vB of this witnessless fixture is 4.07 sat/vB, rounded up
        assertEquals(OnchainPreparedReceipt("cd".repeat(32), inputs, attempt.address, 1_000uL, 5uL, 281uL), receipt)
        val upperCase = payment.copy(outputs = listOf(paid(1000, attempt.address.uppercase()), output(18719, 1u)))
        assertEquals(receipt, lostCandidateReceipt("cd".repeat(32), upperCase, attempt) { parent })
        val other = TxInput("ab".repeat(32), 0u, "", emptyList(), UInt.MAX_VALUE - 2u)
        val refused = listOf(
            payment.copy(outputs = listOf(paid(999), output(18720, 1u))),
            payment.copy(outputs = listOf(paid(1000, "bcrt1qother"), output(18719, 1u))),
            payment.copy(outputs = listOf(paid(500), paid(500), output(18719, 1u))),
            payment.copy(inputs = listOf(input, other)),
            payment.copy(inputs = listOf(other)),
        )
        for (spend in refused) {
            assertNull(lostCandidateReceipt("cd".repeat(32), spend, attempt) { parent })
        }
        assertNull(lostCandidateReceipt("cd".repeat(32), payment, attempt) { null })
        assertNull(lostCandidateReceipt("cd".repeat(32), payment, attempt.copy(originalInputs = null)) { parent })
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
                parent.copy(
                    outputs = listOf(output(Long.MAX_VALUE, 1u), output(Long.MAX_VALUE, 2u), output(Long.MAX_VALUE, 3u))
                )
            }
        }
    }
}
