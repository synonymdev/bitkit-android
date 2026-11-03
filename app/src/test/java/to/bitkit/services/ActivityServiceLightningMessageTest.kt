package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityServiceLightningMessageTest : BaseUnitTest() {
    companion object {
        private const val BITKIT_CORE_FFI_CLASS = "com.synonym.bitkitcore.Bitkitcore_androidKt"
        private const val HASH = "payment-hash"
        private const val COMMENT = "thanks"
        private val DESCRIPTION_HASH = "a".repeat(64)
    }

    private val cacheStore = mock<CacheStore>()
    private val lightningService = mock<LightningService>()

    private var row: Activity? = null

    @Test
    fun `set message does not write back a row the payment sync replaced meanwhile`() = coreTest {
        row = Activity.Lightning(sent(status = PaymentState.PENDING, message = DESCRIPTION_HASH))
        val succeeded = sent(status = PaymentState.SUCCEEDED, message = DESCRIPTION_HASH, fee = 1uL, preimage = "pre")
        whenever(lightningService.listPayments()).thenAnswer {
            row = Activity.Lightning(succeeded)
            listOf(payment())
        }

        sut().setLightningMessageIfEmpty(HASH, COMMENT)

        assertEquals(succeeded.copy(message = COMMENT), (row as Activity.Lightning).v1)
        verify(cacheStore).removePendingLightningMessage(HASH)
    }

    @Test
    fun `set message keeps the pending comment when the row does not exist yet`() = coreTest {
        whenever(lightningService.listPayments()).thenReturn(listOf(payment()))

        sut().setLightningMessageIfEmpty(HASH, COMMENT)

        assertNull(row)
        verify(cacheStore, never()).removePendingLightningMessage(any())
    }

    @Test
    fun `payment sync creates the row with the pending comment and clears it`() = coreTest {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(pendingLightningMessages = mapOf(HASH to COMMENT))))

        sut().syncLdkNodePaymentsToActivities(listOf(payment()))

        val stored = (row as Activity.Lightning).v1
        assertEquals(COMMENT, stored.message)
        assertEquals(PaymentState.SUCCEEDED, stored.status)
        verify(cacheStore).removePendingLightningMessage(HASH)
    }

    @Test
    fun `payment sync replaces the description hash on an existing row with the pending comment`() = coreTest {
        row = Activity.Lightning(sent(status = PaymentState.PENDING, message = DESCRIPTION_HASH))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(pendingLightningMessages = mapOf(HASH to COMMENT))))

        sut().syncLdkNodePaymentsToActivities(listOf(payment()))

        val stored = (row as Activity.Lightning).v1
        assertEquals(COMMENT, stored.message)
        assertEquals(PaymentState.SUCCEEDED, stored.status)
        verify(cacheStore).removePendingLightningMessage(HASH)
    }

    @Test
    fun `payment sync keeps a comment stored while it read the pending messages`() = coreTest {
        row = Activity.Lightning(sent(status = PaymentState.PENDING, message = DESCRIPTION_HASH))
        val commentStoredMeanwhile: Flow<AppCacheData> = flow {
            row = Activity.Lightning(sent(status = PaymentState.PENDING, message = COMMENT))
            emit(AppCacheData())
        }
        whenever(cacheStore.data).thenReturn(commentStoredMeanwhile)

        sut().syncLdkNodePaymentsToActivities(listOf(payment()))

        val stored = (row as Activity.Lightning).v1
        assertEquals(COMMENT, stored.message)
        assertEquals(PaymentState.SUCCEEDED, stored.status)
    }

    private fun coreTest(block: suspend () -> Unit) = test {
        ServiceQueue.CORE.background {
            mockStatic(Class.forName(BITKIT_CORE_FFI_CLASS)).use { native ->
                native.`when`<Activity?> { getActivityById(any(), any()) }.thenAnswer { row }
                native.`when`<Unit> { updateActivity(any(), any()) }.thenAnswer {
                    row = it.arguments[1] as Activity
                    null
                }
                native.`when`<Unit> { upsertActivity(any()) }.thenAnswer {
                    row = it.arguments[0] as Activity
                    null
                }
                block()
            }
        }
    }

    private fun sut() = ActivityService(
        coreService = mock(),
        cacheStore = cacheStore,
        lightningService = lightningService,
        settingsStore = mock(),
        privatePaykitContactResolver = mock(),
    )

    private fun sent(
        status: PaymentState,
        message: String,
        fee: ULong = 0uL,
        preimage: String? = null,
    ) = LightningActivity.create(
        id = HASH,
        txType = PaymentType.SENT,
        status = status,
        value = 22uL,
        invoice = "invoice",
        timestamp = 100uL,
        fee = fee,
        message = message,
        preimage = preimage,
    )

    private fun payment() = PaymentDetails(
        id = HASH,
        kind = PaymentKind.Bolt11(
            hash = HASH,
            preimage = "pre",
            secret = null,
            description = DESCRIPTION_HASH,
            bolt11 = "invoice",
        ),
        amountMsat = 22_000uL,
        feePaidMsat = 1_000uL,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 200uL,
    )
}
