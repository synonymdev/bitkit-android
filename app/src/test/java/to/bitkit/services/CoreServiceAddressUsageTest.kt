package to.bitkit.services

import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TxOutput
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
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

    private fun output(address: String): TxOutput = mock {
        on { scriptpubkeyAddress }.thenReturn(address)
    }
}
