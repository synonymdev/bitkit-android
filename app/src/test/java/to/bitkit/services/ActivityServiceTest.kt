package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.scopedActivityId
import org.mockito.kotlin.mock
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest

class ActivityServiceTest : BaseUnitTest() {
    companion object {
        private const val WALLET_ID = "bitkit"
        private const val ACTIVITY_ID = "activity"
        private const val SEEN_AT = 1_790_000_000uL
    }

    private val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    private val getActivityById = binding.getMethod("getActivityById", String::class.java, String::class.java)
    private val updateActivity = binding.getMethod("updateActivity", String::class.java, Activity::class.java)
    private val markActivityAsSeen = binding.methods.single { it.name.startsWith("markActivityAsSeen") }

    private val cacheStore = mock<CacheStore>()

    private val sut by lazy {
        ActivityService(
            coreService = mock(),
            cacheStore = cacheStore,
            lightningService = mock(),
            settingsStore = mock(),
            privatePaykitContactResolver = mock(),
        )
    }

    @Test
    fun `markActivityAsSeen persists the timestamp through the core seen call`() = test {
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getActivityById.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenReturn(activity())

                sut.markActivityAsSeen(ACTIVITY_ID, walletId = WALLET_ID, seenAt = SEEN_AT)

                native.verify { markActivityAsSeen.invoke(null, WALLET_ID, ACTIVITY_ID, SEEN_AT.toLong()) }
                native.verify({ updateActivity.invoke(null, anyString(), any(Activity::class.java)) }, never())
            }
        }
    }

    @Test
    fun `markActivityAsSeen skips the core seen call when the activity is missing`() = test {
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getActivityById.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenReturn(null)

                sut.markActivityAsSeen(ACTIVITY_ID, walletId = WALLET_ID, seenAt = SEEN_AT)

                native.verify({ markActivityAsSeen.invoke(null, anyString(), anyString(), anyLong()) }, never())
            }
        }
    }

    @Test
    fun `restored contact fills missing attribution and preserves a later edit`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            for (existingContact in listOf(null, "later-contact")) {
                var saved = activity().v1.copy(txType = PaymentType.SENT, contact = existingContact)
                mockStatic(binding).use { native ->
                    native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { saved }
                    native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                        saved = (it.getArgument<Activity>(0) as Activity.Onchain).v1
                        Unit
                    }
                    sut.restoreSentOnchainContact(ACTIVITY_ID, WALLET_ID, "original-contact")
                    kotlin.test.assertEquals(existingContact ?: "original-contact", saved.contact)
                    if (existingContact != null) {
                        native.verify({ upsert.invoke(null, any(Activity::class.java)) }, never())
                    }
                }
            }
        }
    }

    @Test
    fun `restored contact preserves durable manual detachment without blocking completion`() = test {
        whenever(cacheStore.data).thenReturn(
            flowOf(AppCacheData(detachedActivityContacts = setOf(scopedActivityId(WALLET_ID, ACTIVITY_ID))))
        )
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            var saved = activity().v1.copy(txType = PaymentType.SENT, contact = null)
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { saved }
                native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                    saved = (it.getArgument<Activity>(0) as Activity.Onchain).v1
                    Unit
                }
                sut.restoreSentOnchainContact(ACTIVITY_ID, WALLET_ID, "original-contact")
                kotlin.test.assertNull(saved.contact)
                native.verify({ upsert.invoke(null, any(Activity::class.java)) }, never())
            }
        }
    }

    private fun activity() = Activity.Onchain(
        OnchainActivity.create(
            walletId = WALLET_ID,
            id = ACTIVITY_ID,
            txType = PaymentType.RECEIVED,
            txId = ACTIVITY_ID,
            value = 1uL,
            fee = 0uL,
            address = "address",
            timestamp = 1uL,
        )
    )
}
