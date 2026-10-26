package to.bitkit.repositories

import breez_sdk_spark.GetInfoResponse
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class SparkRepoTest : BaseUnitTest() {
    private val sparkService = mock<SparkService>()

    private fun createSut() = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { mock() },
        settingsStore = mock(),
    )

    @Test
    fun `lists a broadcast deposit as pending until Spark lists it`() = test {
        whenever { sparkService.getInfo() }.thenReturn(GetInfoResponse("pubkey", 0uL, emptyMap(), 0uL))
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(emptyList())
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        val sut = createSut()

        sut.onDepositBroadcast("txid", 2_500uL)

        val deposit = sut.state.value.deposits.single()
        assertEquals("txid", deposit.txid)
        assertEquals(false, deposit.isClaimed)
        assertEquals(2_500uL, sut.state.value.pendingDepositSats)
    }
}
