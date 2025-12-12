package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ActivityDetailViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDetailViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val activityRepo = mock<ActivityRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()

    private lateinit var sut: ActivityDetailViewModel

    @Before
    fun setUp() {
        whenever(context.getString(R.string.wallet__activity_error_not_found))
            .thenReturn("Activity not found")
        whenever(context.getString(R.string.wallet__activity_error_load_failed))
            .thenReturn("Failed to load activity")
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(System.currentTimeMillis()))

        sut = ActivityDetailViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            activityRepo = activityRepo,
            blocktankRepo = blocktankRepo,
            settingsStore = settingsStore,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `findOrderForTransfer returns null when both channelId and txId are null`() = test {
        val result = sut.findOrderForTransfer(null, null)

        assertNull(result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId`() = test {
        val orderId = "test-order-id"
        val mockOrder = mock<IBtOrder> {
            on { id } doReturn orderId
        }

        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = listOf(mockOrder)))
        )

        val result = sut.findOrderForTransfer(orderId, null)

        assertEquals(mockOrder, result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId matching order id`() = test {
        val orderId = "order-123"
        val mockOrder = mock<IBtOrder> {
            on { id } doReturn orderId
        }

        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = listOf(mockOrder)))
        )

        val result = sut.findOrderForTransfer(orderId, null)

        assertEquals(mockOrder, result)
    }

    @Test
    fun `findOrderForTransfer returns null when order not found`() = test {
        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = emptyList()))
        )

        val result = sut.findOrderForTransfer("non-existent-id", null)

        assertNull(result)
    }

    @Test
    fun `loadActivity starts observation of activity changes`() = test {
        val activityId = "test-activity-1"
        val initialActivity = createTestActivity(activityId, confirmed = false)
        val updatedActivity = createTestActivity(activityId, confirmed = true)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.success(initialActivity))
        whenever(activityRepo.getActivityTags(activityId))
            .thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(activityId)

        // Verify initial state loaded
        val initialState = sut.uiState.value.activityLoadState
        assertTrue(initialState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(initialActivity, (initialState as ActivityDetailViewModel.ActivityLoadState.Success).activity)

        // Simulate activity update
        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.success(updatedActivity))
        activitiesChangedFlow.value = System.currentTimeMillis()

        // Verify ViewModel reflects updated activity
        val updatedState = sut.uiState.value.activityLoadState
        assertTrue(updatedState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(updatedActivity, (updatedState as ActivityDetailViewModel.ActivityLoadState.Success).activity)
    }

    @Test
    fun `clearActivityState stops observation`() = test {
        val activityId = "test-activity-1"
        val activity = createTestActivity(activityId)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(activityId))
            .thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(activityId)

        // Clear state
        sut.clearActivityState()

        // Trigger activity change
        val callCountBefore = org.mockito.kotlin.mockingDetails(activityRepo).invocations.size
        activitiesChangedFlow.value = System.currentTimeMillis()

        // Verify no reload after clear (getActivity not called again)
        val callCountAfter = org.mockito.kotlin.mockingDetails(activityRepo).invocations.size
        assertEquals(callCountBefore, callCountAfter)
    }

    @Test
    fun `reloadActivity keeps last state on failure`() = test {
        val activityId = "test-activity-1"
        val activity = createTestActivity(activityId)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(activityId))
            .thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(activityId)

        // Simulate reload failure
        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.failure(Exception("Network error")))
        activitiesChangedFlow.value = System.currentTimeMillis()

        // Verify last known state is preserved
        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(activity, (state as ActivityDetailViewModel.ActivityLoadState.Success).activity)
    }

    @Test
    fun `loadActivity handles error gracefully`() = test {
        val activityId = "test-activity-1"

        whenever(activityRepo.getActivity(activityId))
            .thenReturn(Result.failure(Exception("Database error")))

        sut.loadActivity(activityId)

        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Error)
    }

    private fun createTestActivity(
        id: String,
        confirmed: Boolean = false,
    ): Activity.Onchain {
        return Activity.Onchain(
            v1 = OnchainActivity(
                id = id,
                txType = PaymentType.RECEIVED,
                txId = "tx-$id",
                value = 100000UL,
                fee = 500UL,
                feeRate = 8UL,
                address = "bc1...",
                confirmed = confirmed,
                timestamp = (System.currentTimeMillis() / 1000).toULong(),
                isBoosted = false,
                boostTxIds = emptyList(),
                isTransfer = false,
                doesExist = true,
                confirmTimestamp = if (confirmed) (System.currentTimeMillis() / 1000).toULong() else null,
                channelId = null,
                transferTxId = null,
                createdAt = null,
                updatedAt = null,
            )
        )
    }
}
