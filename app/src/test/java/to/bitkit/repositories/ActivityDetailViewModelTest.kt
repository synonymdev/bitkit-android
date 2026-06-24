package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.models.ActivityWalletType
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ActivityDetailViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDetailViewModelTest : BaseUnitTest() {
    private lateinit var sut: ActivityDetailViewModel

    private val context = mock<Context>()
    private val activityRepo = mock<ActivityRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val transferRepo = mock<TransferRepo>()
    private val hardwareWalletId = ActivityWalletType.TREZOR.idPrefixed("dev1")

    companion object Fixtures {
        const val ACTIVITY_ID = "test-activity-1"
        const val ORDER_ID = "test-order-id"
    }

    @Before
    fun setUp() {
        whenever(context.getString(R.string.wallet__activity_error_not_found)).thenReturn("Activity not found")
        whenever(context.getString(R.string.wallet__activity_error_load_failed)).thenReturn("Failed to load activity")
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(System.currentTimeMillis()))
        runBlocking {
            whenever(transferRepo.findLspOrderIdByFundingTxId(any())).thenReturn(Result.success(null))
        }

        sut = ActivityDetailViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            activityRepo = activityRepo,
            blocktankRepo = blocktankRepo,
            settingsStore = settingsStore,
            transferRepo = transferRepo,
        )
    }

    @Test
    fun `loadActivity resolves a hardware wallet activity and tags it via its wallet id`() = test {
        val hwActivity = Activity.Onchain(
            OnchainActivity.create(
                id = ACTIVITY_ID,
                txType = PaymentType.RECEIVED,
                txId = ACTIVITY_ID,
                value = 100_000uL,
                fee = 0uL,
                address = "",
                timestamp = 1_700_000_000uL,
                confirmed = true,
                walletId = hardwareWalletId,
            )
        )
        whenever { activityRepo.getActivity(ACTIVITY_ID, hardwareWalletId) }.thenReturn(Result.success(hwActivity))
        whenever { activityRepo.getActivityTags(ACTIVITY_ID, hardwareWalletId) }.thenReturn(Result.success(emptyList()))
        whenever {
            activityRepo.addTagsToActivity(ACTIVITY_ID, listOf("tag1"), hardwareWalletId)
        }.thenReturn(Result.success(Unit))
        whenever { settingsStore.addLastUsedTag("tag1") }.thenReturn(Unit)

        sut.loadActivity(ACTIVITY_ID, hardwareWalletId)
        val loadState = sut.uiState.value.activityLoadState
        assertTrue(loadState is ActivityDetailViewModel.ActivityLoadState.Success)
        val activity = loadState.activity
        assertTrue(activity is Activity.Onchain)
        assertEquals(ACTIVITY_ID, activity.v1.id)
        assertEquals(hardwareWalletId, activity.v1.walletId)

        sut.addTag("tag1")

        verify(activityRepo, atLeastOnce()).getActivity(ACTIVITY_ID, hardwareWalletId)
        verify(activityRepo).addTagsToActivity(ACTIVITY_ID, listOf("tag1"), hardwareWalletId)
        verify(activityRepo, atLeastOnce()).getActivityTags(ACTIVITY_ID, hardwareWalletId)
    }

    @Test
    fun `loadActivity reports not found when missing from the database`() = test {
        whenever { activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull()) }.thenReturn(Result.success(null))

        sut.loadActivity(ACTIVITY_ID)

        val state = sut.uiState.value
        assertTrue(state.activityLoadState is ActivityDetailViewModel.ActivityLoadState.Error)
    }

    @Test
    fun `findOrderForTransfer returns null when both channelId and txId are null`() = test {
        val result = sut.findOrderForTransfer(null, null)

        assertNull(result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId`() = test {
        val order = mock<IBtOrder> { on { id } doReturn ORDER_ID }
        val state = BlocktankState(orders = listOf(order).toImmutableList())
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(state))

        val result = sut.findOrderForTransfer(ORDER_ID, null)

        assertEquals(order, result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId matching order id`() = test {
        val order = mock<IBtOrder> { on { id } doReturn ORDER_ID }
        val state = BlocktankState(orders = listOf(order).toImmutableList())
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(state))

        val result = sut.findOrderForTransfer(ORDER_ID, null)

        assertEquals(order, result)
    }

    @Test
    fun `findOrderForTransfer returns null when order not found`() = test {
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState(orders = persistentListOf())))

        val result = sut.findOrderForTransfer("non-existent-id", null)

        assertNull(result)
    }

    @Test
    fun `findOrderForTransfer finds pending order by transfer funding txId`() = test {
        val txId = "funding-tx-id"
        val order = mock<IBtOrder> { on { id } doReturn ORDER_ID }
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState(orders = persistentListOf())))
        whenever(transferRepo.findLspOrderIdByFundingTxId(txId)).thenReturn(Result.success(ORDER_ID))
        whenever(blocktankRepo.getOrder(eq(ORDER_ID), eq(false))).thenReturn(Result.success(order))

        val result = sut.findOrderForTransfer(null, txId)

        assertEquals(order, result)
    }

    @Test
    fun `loadActivity starts observation of activity changes`() = test {
        val initialActivity = createTestActivity(ACTIVITY_ID, confirmed = false)
        val updatedActivity = createTestActivity(ACTIVITY_ID, confirmed = true)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(initialActivity))
        whenever(activityRepo.getActivityTags(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(ACTIVITY_ID)

        // Verify initial state loaded
        val initialState = sut.uiState.value.activityLoadState
        assertTrue(initialState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertTrue(initialState.activity is Activity.Onchain)
        assertEquals(initialActivity.v1.id, initialState.activity.v1.id)
        assertEquals(initialActivity.v1.confirmed, initialState.activity.v1.confirmed)

        // Simulate activity update
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(updatedActivity))
        activitiesChangedFlow.value += 1

        // Verify ViewModel reflects updated activity
        val updatedState = sut.uiState.value.activityLoadState
        assertTrue(updatedState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertTrue(updatedState.activity is Activity.Onchain)
        assertEquals(updatedActivity.v1.id, updatedState.activity.v1.id)
        assertEquals(updatedActivity.v1.confirmed, updatedState.activity.v1.confirmed)
    }

    @Test
    fun `clearActivityState stops observation`() = test {
        val activity = createTestActivity(ACTIVITY_ID)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(ACTIVITY_ID)

        // Clear state
        sut.clearActivityState()

        // Trigger activity change
        val callCountBefore = mockingDetails(activityRepo).invocations.size
        activitiesChangedFlow.value += 1

        // Verify no reload after clear (getActivity not called again)
        val callCountAfter = mockingDetails(activityRepo).invocations.size
        assertEquals(callCountBefore, callCountAfter)
    }

    @Test
    fun `reloadActivity keeps last state on failure`() = test {
        val activity = createTestActivity(ACTIVITY_ID)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(eq(ACTIVITY_ID), anyOrNull())).thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(ACTIVITY_ID)

        // Simulate reload failure
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull()))
            .thenReturn(Result.failure(Exception("Network error")))
        activitiesChangedFlow.value += 1

        // Verify last known state is preserved
        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Success)
        assertTrue(state.activity is Activity.Onchain)
        assertEquals(activity.v1.id, state.activity.v1.id)
        assertEquals(activity.v1.confirmed, state.activity.v1.confirmed)
    }

    @Test
    fun `loadActivity handles error gracefully`() = test {
        whenever(activityRepo.getActivity(eq(ACTIVITY_ID), anyOrNull()))
            .thenReturn(Result.failure(Exception("Database error")))

        sut.loadActivity(ACTIVITY_ID)

        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Error)
    }

    private fun createTestActivity(
        id: String,
        confirmed: Boolean = false,
    ): Activity.Onchain {
        return Activity.Onchain(
            v1 = OnchainActivity.create(
                id = id,
                txType = PaymentType.RECEIVED,
                txId = "tx-$id",
                value = 100000UL,
                fee = 500UL,
                feeRate = 8UL,
                address = "bc1...",
                confirmed = confirmed,
                timestamp = (System.currentTimeMillis() / 1000).toULong(),
                confirmTimestamp = if (confirmed) (System.currentTimeMillis() / 1000).toULong() else null,
            )
        )
    }
}
