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

    private val sut by lazy {
        ActivityService(
            coreService = mock(),
            cacheStore = mock(),
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
