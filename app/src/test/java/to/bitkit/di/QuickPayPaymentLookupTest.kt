package to.bitkit.di

import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.QuickPayReconcileRow
import to.bitkit.repositories.SparkRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import breez_sdk_spark.PaymentStatus as SparkPaymentStatus

class QuickPayPaymentLookupTest : BaseUnitTest() {
    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val lookup = RepoModule.provideQuickPayPaymentLookup(lightningRepo, sparkRepo)

    @Test
    fun `a spark retry still pending outranks an older failed channel attempt`() = test {
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.FAILED)))
        whenever(sparkRepo.lightningSendStatuses()).thenReturn(mapOf("hash" to SparkPaymentStatus.PENDING))

        val row = lookup.rows()?.single { it.invoicePaymentHash == "hash" }

        assertEquals(QuickPayReconcileRow.Status.PENDING, row?.status)
    }

    @Test
    fun `a spark send that paid settles the payment the channel failed`() = test {
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.FAILED)))
        whenever(sparkRepo.lightningSendStatuses()).thenReturn(mapOf("hash" to SparkPaymentStatus.COMPLETED))

        val row = lookup.rows()?.single { it.invoicePaymentHash == "hash" }

        assertEquals(QuickPayReconcileRow.Status.SUCCEEDED, row?.status)
    }

    @Test
    fun `the lookup is unavailable while spark cannot list its payments`() = test {
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.FAILED)))
        whenever(sparkRepo.lightningSendStatuses()).thenReturn(null)

        assertNull(lookup.rows())
    }

    private fun nodeSend(status: PaymentStatus) = PaymentDetails(
        id = "hash",
        kind = PaymentKind.Bolt11(hash = "hash", preimage = null, secret = null, description = null, bolt11 = "bolt11"),
        amountMsat = 1_000_000uL,
        feePaidMsat = null,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = 0uL,
    )
}
