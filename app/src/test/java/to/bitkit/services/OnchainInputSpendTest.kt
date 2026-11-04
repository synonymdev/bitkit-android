package to.bitkit.services

import org.junit.Test
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxInput
import org.lightningdevkit.ldknode.TxOutput
import to.bitkit.repositories.OnchainInputSpend
import to.bitkit.repositories.OnchainSendInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OnchainInputSpendTest {
    private val spendTxid = "EF".repeat(32)
    private val guarded = OnchainSendInput("ab".repeat(32), 1u)
    private val confirmed = ConfirmationStatus.Confirmed("00".repeat(32), 100u, 200uL)
    private val details = TransactionDetails(
        -300L,
        listOf(TxInput("AB".repeat(32), 1u, "", emptyList(), UInt.MAX_VALUE - 2u)),
        listOf(TxOutput("", null, "bcrt1qown", 546L, 0u), TxOutput("", null, null, 18_000L, 1u)),
    )

    private fun payment(status: PaymentStatus, confirmation: ConfirmationStatus) = PaymentDetails(
        id = spendTxid,
        kind = PaymentKind.Onchain(spendTxid, confirmation),
        amountMsat = 0uL,
        feePaidMsat = 300_000uL,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = 200uL,
    )

    @Test
    fun `spend is final only once the node reports its confirmation succeeded`() {
        assertEquals(
            OnchainInputSpend(
                txid = "ef".repeat(32),
                inputs = listOf(guarded),
                outputAddresses = listOf("bcrt1qown", null),
                isFinal = true,
                confirmations = 6u,
            ),
            onchainInputSpend(payment(PaymentStatus.SUCCEEDED, confirmed), details, listOf(guarded), 105u),
        )
        val confirming = onchainInputSpend(payment(PaymentStatus.PENDING, confirmed), details, listOf(guarded), 101u)
        assertFalse(requireNotNull(confirming).isFinal)
        assertEquals(2u, confirming.confirmations)
        val unconfirmed = payment(PaymentStatus.SUCCEEDED, ConfirmationStatus.Unconfirmed)
        val pending = requireNotNull(onchainInputSpend(unconfirmed, details, listOf(guarded), 105u))
        assertFalse(pending.isFinal)
        assertEquals(0u, pending.confirmations)
    }

    @Test
    fun `transactions without a guarded input or on-chain kind are not spends`() {
        val final = payment(PaymentStatus.SUCCEEDED, confirmed)
        assertNull(onchainInputSpend(final, details, listOf(guarded.copy(vout = 0u))))
        assertNull(onchainInputSpend(final, details, listOf(OnchainSendInput("cd".repeat(32), 1u))))
        val lightning = final.copy(kind = PaymentKind.Spontaneous("11".repeat(32), null))
        assertNull(onchainInputSpend(lightning, details, listOf(guarded)))
    }
}
