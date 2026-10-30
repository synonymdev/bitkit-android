package to.bitkit.services

import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TxOutput
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreServiceAddressUsageTest : BaseUnitTest() {
    private val sut = mock<CoreService>()
    private val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    private val activityUsage = binding.getMethod("isAddressUsed", String::class.java)
    private val transactionHistory = binding.getMethod("getAllTransactionDetails")

    @Test
    fun `address usage includes secondary transaction outputs without activity attribution`() = test {
        whenever(sut.isAddressUsed(any())).thenCallRealMethod()
        val details = TransactionDetails(
            walletId = "wallet0",
            txId = "batch-payment",
            amountSats = 3000,
            inputs = emptyList(),
            outputs = listOf(output("receive-address"), output("public-address")),
        )
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Boolean> { activityUsage.invoke(null, "public-address") }.thenReturn(false)
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }.thenReturn(listOf(details))

                assertTrue(sut.isAddressUsed("public-address"))
            }
        }
    }

    @Test
    fun `address usage leaves unrelated outputs unused and retains activity matches`() = test {
        whenever(sut.isAddressUsed(any())).thenCallRealMethod()
        val details = TransactionDetails("wallet0", "other-payment", 1, emptyList(), listOf(output("other-address")))
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Boolean> { activityUsage.invoke(null, "unused-address") }.thenReturn(false)
                native.`when`<Boolean> { activityUsage.invoke(null, "activity-address") }.thenReturn(true)
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }.thenReturn(listOf(details))

                assertFalse(sut.isAddressUsed("unused-address"))
                assertTrue(sut.isAddressUsed("activity-address"))
            }
        }
    }

    @Test
    fun `failed transaction history cannot report an address unused`() = test {
        whenever(sut.isAddressUsed(any())).thenCallRealMethod()
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Boolean> { activityUsage.invoke(null, "public-address") }.thenReturn(false)
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }
                    .thenThrow(IllegalStateException("Transaction history unavailable"))

                assertFailsWith<AppError> { sut.isAddressUsed("public-address") }
            }
        }
    }

    @Test
    fun `activity matches and empty address batches do not read transaction history`() = test {
        whenever(sut.getAddressUsage(any())).thenCallRealMethod()
        var historyReads = 0
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Boolean> { activityUsage.invoke(null, "activity-address") }.thenReturn(true)
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }.thenAnswer {
                    historyReads++
                    emptyList<TransactionDetails>()
                }

                assertTrue(sut.getAddressUsage(emptyList()).isEmpty())
                assertTrue(sut.getAddressUsage(listOf("activity-address")).getValue("activity-address").getOrThrow())
                assertEquals(0, historyReads)
            }
        }
    }

    @Test
    fun `batch usage retains activity positives and isolates failures without swallowing cancellation`() = test {
        whenever(sut.getAddressUsage(any())).thenCallRealMethod()
        var historyReads = 0
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Boolean> { activityUsage.invoke(null, "activity-address") }.thenReturn(true)
                native.`when`<Boolean> { activityUsage.invoke(null, "activity-error") }
                    .thenThrow(IllegalStateException("Activity unavailable"))
                native.`when`<Boolean> { activityUsage.invoke(null, "history-error") }.thenReturn(false)
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }.thenAnswer {
                    historyReads++
                    error("Transaction history unavailable")
                }

                val usage = sut.getAddressUsage(listOf("activity-error", "history-error", "activity-address"))
                assertTrue(usage.getValue("activity-address").getOrThrow())
                assertTrue(usage.getValue("activity-error").isFailure)
                assertTrue(usage.getValue("history-error").isFailure)
                assertEquals(1, historyReads)

                native.`when`<Boolean> { activityUsage.invoke(null, "cancelled") }
                    .thenThrow(CancellationException("cancelled"))
                assertFailsWith<CancellationException> { sut.getAddressUsage(listOf("cancelled")) }
                assertEquals(1, historyReads)
            }
        }
    }

    private fun output(address: String): TxOutput = mock {
        on { scriptpubkeyAddress }.thenReturn(address)
    }
}
