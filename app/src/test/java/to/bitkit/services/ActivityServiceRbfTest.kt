package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.getActivityByTxId
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
import com.synonym.bitkitcore.upsertOnchainActivityPreservingFeeRate
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest

class ActivityServiceRbfTest : BaseUnitTest() {
    companion object {
        private const val BINDING_CLASS = "com.synonym.bitkitcore.Bitkitcore_androidKt"
        private const val WALLET_ID = "wallet0"
        private const val TX_ID = "replacement-tx"
        private const val ACTIVITY_ID = "stored-payment-id"
    }

    private val cacheStore = mock<CacheStore>()
    private val lightningService = mock<LightningService>()

    @Test
    fun `recordRbfBoost calls the atomic core API with the requested wallet and rate`() = test {
        ServiceQueue.CORE.background {
            val binding = Class.forName(BINDING_CLASS)
            val record = binding.methods.single { it.name.startsWith("recordRbfBoost") }
            mockStatic(binding).use { native ->
                service().recordRbfBoost(ACTIVITY_ID, TX_ID, 25uL, WALLET_ID)

                native.verify { record.invoke(null, WALLET_ID, ACTIVITY_ID, TX_ID, 25L) }
                native.verify({ updateActivity(any(), any()) }, never())
            }
        }
    }

    @Test
    fun `payment sync merges an existing transaction through the fee preserving API`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        ServiceQueue.CORE.background {
            mockStatic(Class.forName(BINDING_CLASS)).use { native ->
                native.`when`<Activity?> { getActivityById(any(), any()) }.thenReturn(null)
                native.`when`<OnchainActivity?> { getActivityByTxId(any(), any()) }.thenReturn(activity())

                service().syncLdkNodePaymentsToActivities(listOf(payment()))

                native.verify {
                    upsertOnchainActivityPreservingFeeRate(
                        argThat { walletId == WALLET_ID && txId == TX_ID && feeRate == 25uL && confirmed }
                    )
                }
                native.verify({ updateActivity(any(), any()) }, never())
                native.verify({ upsertActivity(any()) }, never())
            }
        }
    }

    @Test
    fun `reorg events preserve the rate at the core boundary`() = eventTest {
        service().handleOnchainTransactionReorged(TX_ID)
    }

    @Test
    fun `eviction events preserve the rate at the core boundary`() = eventTest {
        service().handleOnchainTransactionEvicted(TX_ID)
    }

    private fun eventTest(block: suspend () -> Unit) = test {
        ServiceQueue.CORE.background {
            mockStatic(Class.forName(BINDING_CLASS)).use { native ->
                native.`when`<OnchainActivity?> { getActivityByTxId(any(), any()) }.thenReturn(activity())
                block()
                native.verify {
                    upsertOnchainActivityPreservingFeeRate(argThat { txId == TX_ID && feeRate == 25uL })
                }
                native.verify({ updateActivity(any(), any()) }, never())
            }
        }
    }

    private fun service() = ActivityService(
        coreService = mock(),
        cacheStore = cacheStore,
        lightningService = lightningService,
        settingsStore = mock(),
        privatePaykitContactResolver = mock(),
    )

    private fun activity() = OnchainActivity.create(
        walletId = WALLET_ID,
        id = ACTIVITY_ID,
        txType = PaymentType.SENT,
        txId = TX_ID,
        value = 10_000uL,
        fee = 2_500uL,
        feeRate = 25uL,
        address = "bcrt1qrecipient",
        timestamp = 100uL,
        updatedAt = 100uL,
    )

    private fun payment() = PaymentDetails(
        id = "new-payment-id",
        kind = PaymentKind.Onchain(
            txid = TX_ID,
            status = ConfirmationStatus.Confirmed(blockHash = "block", height = 100u, timestamp = 200uL),
        ),
        amountMsat = 10_000_000uL,
        feePaidMsat = 2_500_000uL,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 200uL,
    )
}
