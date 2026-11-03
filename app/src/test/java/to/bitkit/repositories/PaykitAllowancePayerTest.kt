package to.bitkit.repositories

import com.synonym.paykit.PaymentOutcome
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.WalletScope
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaykitAllowancePayerTest : BaseUnitTest() {
    private val paymentProofRepo = mock<PaykitPaymentProofRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val sut = PaykitAllowancePayer(mock(), paymentProofRepo, lightningRepo, mock())

    private val requestId = PaykitPaymentRequestId(paymentRequestId = "req-1", counterparty = "payee")
    private val txid = "ab".repeat(32)

    @Before
    fun setUp() = test {
        whenever(paymentProofRepo.hasVerifiedOnchainProof(any(), any())).thenReturn(false)
    }

    @Test
    fun `refused or unknown broadcast stays unresolved`() = test {
        for (evidence in listOf(OnchainSendEvidence.Rejected, OnchainSendEvidence.Unknown)) {
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt(evidence))

            assertNull(sut.onchainPaymentOutcome(requestId, txid))
        }
    }

    @Test
    fun `accepted or observed attempt for the request succeeds`() = test {
        for (evidence in listOf(OnchainSendEvidence.Accepted, OnchainSendEvidence.Observed)) {
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt(evidence))

            assertEquals(PaymentOutcome.SUCCEEDED, sut.onchainPaymentOutcome(requestId, txid))
        }
    }

    @Test
    fun `accepted attempt for another request proves nothing`() = test {
        whenever(lightningRepo.currentOnchainSendAttempt())
            .thenReturn(
                attempt(OnchainSendEvidence.Accepted).copy(requestId = requestId.copy(paymentRequestId = "req-2"))
            )

        assertNull(sut.onchainPaymentOutcome(requestId, txid))
    }

    @Test
    fun `verified proof succeeds after the attempt was replaced`() = test {
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(null)
        whenever(paymentProofRepo.hasVerifiedOnchainProof(requestId, txid)).thenReturn(true)

        assertEquals(PaymentOutcome.SUCCEEDED, sut.onchainPaymentOutcome(requestId, txid))
    }

    @Test
    fun `unreadable attempt and no proof stays unresolved`() = test {
        whenever(lightningRepo.currentOnchainSendAttempt()).thenThrow(IllegalStateException("unreadable"))

        assertNull(sut.onchainPaymentOutcome(requestId, txid))
    }

    private fun attempt(evidence: OnchainSendEvidence) = OnchainSendAttempt(
        walletId = WalletScope.default, attemptId = "attempt", requestId = requestId,
        orderId = null, address = "address", amountSats = 1_000uL, isMaxAmount = false,
        feeRateSatsPerVByte = 2uL, isTransfer = false, channelId = null, tags = emptyList(),
        txid = txid, evidence = evidence, candidateTxids = listOf(txid),
    )
}
