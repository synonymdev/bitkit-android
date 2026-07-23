package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TransactionDetails
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ActivityDetailViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDetailViewModelTest : BaseUnitTest() {
    private lateinit var sut: ActivityDetailViewModel

    private val context = mock<Context>()
    private val activityRepo = mock<ActivityRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val transferRepo = mock<TransferRepo>()

    companion object Fixtures {
        const val ACTIVITY_ID = "test-activity-1"
        const val ORDER_ID = "test-order-id"
        const val HARDWARE_WALLET_ID = "hardware-wallet"
    }

    @Before
    fun setUp() {
        whenever(context.getString(R.string.wallet__activity_error_not_found)).thenReturn("Activity not found")
        whenever(context.getString(R.string.wallet__activity_error_load_failed)).thenReturn("Failed to load activity")
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(System.currentTimeMillis()))
        whenever(hwWalletRepo.activities).thenReturn(MutableStateFlow(persistentListOf()))
        runBlocking {
            whenever(transferRepo.findLspOrderIdByFundingTxId(any())).thenReturn(Result.success(null))
        }

        sut = ActivityDetailViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            activityRepo = activityRepo,
            blocktankRepo = blocktankRepo,
            settingsStore = settingsStore,
            hwWalletRepo = hwWalletRepo,
            transferRepo = transferRepo,
        )
    }

    @Test
    fun `loadActivity falls back to hardware wallet activity when missing from the database`() = test {
        val hwActivity = Activity.Onchain(
            OnchainActivity.create(
                walletId = HARDWARE_WALLET_ID,
                id = ACTIVITY_ID,
                txType = PaymentType.RECEIVED,
                txId = ACTIVITY_ID,
                value = 100_000uL,
                fee = 0uL,
                address = "",
                timestamp = 1_700_000_000uL,
                confirmed = true,
            )
        )
        whenever {
            activityRepo.getActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(null))
        whenever(hwWalletRepo.activities).thenReturn(MutableStateFlow(persistentListOf<Activity>(hwActivity)))

        sut.loadActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)

        val state = sut.uiState.value
        val loadState = state.activityLoadState as ActivityDetailViewModel.ActivityLoadState.Success
        assertEquals(hwActivity, loadState.activity)
        assertTrue(state.isHardwareActivity)
    }

    @Test
    fun `hardware wallet activity updates while loaded`() = test {
        val initialActivity = createTestActivity(ACTIVITY_ID, confirmed = false, walletId = HARDWARE_WALLET_ID)
        val updatedActivity = createTestActivity(ACTIVITY_ID, confirmed = true, walletId = HARDWARE_WALLET_ID)
        val hardwareActivities = MutableStateFlow(persistentListOf<Activity>(initialActivity))

        whenever {
            activityRepo.getActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(null))
        whenever(hwWalletRepo.activities).thenReturn(hardwareActivities)

        sut.loadActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)

        val initialState = sut.uiState.value.activityLoadState
        assertTrue(initialState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(initialActivity, initialState.activity)

        hardwareActivities.value = persistentListOf(updatedActivity)

        val updatedState = sut.uiState.value.activityLoadState
        assertTrue(updatedState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(updatedActivity, updatedState.activity)
        assertTrue(sut.uiState.value.isHardwareActivity)
    }

    @Test
    fun `loadActivity reports not found when missing from database and hardware wallets`() = test {
        whenever { activityRepo.getActivity(ACTIVITY_ID) }.thenReturn(Result.success(null))

        sut.loadActivity(ACTIVITY_ID)

        val state = sut.uiState.value
        assertTrue(state.activityLoadState is ActivityDetailViewModel.ActivityLoadState.Error)
        assertFalse(state.isHardwareActivity)
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
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.success(initialActivity))
        whenever(activityRepo.getActivityTags(ACTIVITY_ID)).thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(ACTIVITY_ID)

        // Verify initial state loaded
        val initialState = sut.uiState.value.activityLoadState
        assertTrue(initialState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(initialActivity, initialState.activity)

        // Simulate activity update
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.success(updatedActivity))
        activitiesChangedFlow.value += 1

        // Verify ViewModel reflects updated activity
        val updatedState = sut.uiState.value.activityLoadState
        assertTrue(updatedState is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(updatedActivity, updatedState.activity)
    }

    @Test
    fun `clearActivityState stops observation`() = test {
        val activity = createTestActivity(ACTIVITY_ID)
        val activitiesChangedFlow = MutableStateFlow(System.currentTimeMillis())

        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChangedFlow)
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(ACTIVITY_ID)).thenReturn(Result.success(emptyList()))

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
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.success(activity))
        whenever(activityRepo.getActivityTags(ACTIVITY_ID)).thenReturn(Result.success(emptyList()))

        // Load activity
        sut.loadActivity(ACTIVITY_ID)

        // Simulate reload failure
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.failure(Exception("Network error")))
        activitiesChangedFlow.value += 1

        // Verify last known state is preserved
        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Success)
        assertEquals(activity, state.activity)
    }

    @Test
    fun `loadActivity handles error gracefully`() = test {
        whenever(activityRepo.getActivity(ACTIVITY_ID)).thenReturn(Result.failure(Exception("Database error")))

        sut.loadActivity(ACTIVITY_ID)

        val state = sut.uiState.value.activityLoadState
        assertTrue(state is ActivityDetailViewModel.ActivityLoadState.Error)
    }

    @Test
    fun `hardware tags and transaction details use the activity wallet scope`() = test {
        val activity = createTestActivity(ACTIVITY_ID, walletId = HARDWARE_WALLET_ID)
        val transactionDetails = mock<TransactionDetails>()
        whenever {
            activityRepo.getActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(activity))
        whenever {
            activityRepo.getActivityTags(ACTIVITY_ID, HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(emptyList()))
        whenever {
            activityRepo.addTagsToActivity(ACTIVITY_ID, listOf("cold"), HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(Unit))
        whenever {
            activityRepo.getTransactionDetails(activity.v1.txId, HARDWARE_WALLET_ID)
        }.thenReturn(Result.success(transactionDetails))

        sut.loadActivity(ACTIVITY_ID, HARDWARE_WALLET_ID)
        sut.addTag("cold")
        sut.fetchTransactionDetails(activity.v1.txId)

        verify(activityRepo).addTagsToActivity(ACTIVITY_ID, listOf("cold"), HARDWARE_WALLET_ID)
        verify(activityRepo).getTransactionDetails(activity.v1.txId, HARDWARE_WALLET_ID)
        assertEquals(transactionDetails, sut.txDetails.value)
        assertFalse(sut.isTxDetailsLoading.value)
    }

    private fun createTestActivity(
        id: String,
        confirmed: Boolean = false,
        walletId: String = "wallet0",
    ): Activity.Onchain {
        return Activity.Onchain(
            v1 = OnchainActivity.create(
                walletId = walletId,
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
