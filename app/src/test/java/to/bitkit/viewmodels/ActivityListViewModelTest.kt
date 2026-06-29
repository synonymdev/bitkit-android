package to.bitkit.viewmodels

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.ext.rawId
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.ActivityState
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityListViewModelTest : BaseUnitTest() {

    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val settingsStore = mock<SettingsStore>()

    private val dbActivity = onchainActivity(id = "db1", txType = PaymentType.SENT, timestamp = 200uL)
    private val hwActivity = onchainActivity(id = "hw1", txType = PaymentType.RECEIVED, timestamp = 100uL)
    private lateinit var hardwareActivities: MutableStateFlow<ImmutableList<Activity>>

    @Before
    fun setUp() {
        hardwareActivities = MutableStateFlow(persistentListOf(hwActivity))
        whenever(activityRepo.state).thenReturn(MutableStateFlow(ActivityState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(0L))
        whenever { activityRepo.syncActivities() }.thenReturn(Result.success(Unit))
        whenever { activityRepo.getTxIdsInBoostTxIds() }.thenReturn(emptySet())
        whenever {
            activityRepo.getActivities(
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        }.thenReturn(Result.success(listOf(dbActivity)))
        whenever(hwWalletRepo.activities).thenReturn(hardwareActivities)
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(settingsStore.isPaykitEnabled).thenReturn(MutableStateFlow(false))
    }

    private fun createViewModel() = ActivityListViewModel(
        bgDispatcher = testDispatcher,
        activityRepo = activityRepo,
        hwWalletRepo = hwWalletRepo,
        pubkyRepo = pubkyRepo,
        settingsStore = settingsStore,
    )

    @Test
    fun `filtered activities merge hardware activities newest first`() = test {
        val sut = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("db1", "hw1"), sut.filteredActivities.value?.map { it.rawId() })
    }

    @Test
    fun `filtered activities exclude hardware activities not matching the tab`() = test {
        val sut = createViewModel()
        sut.setTab(ActivityTab.SENT)
        advanceUntilIdle()

        assertEquals(listOf("db1"), sut.filteredActivities.value?.map { it.rawId() })
    }

    @Test
    fun `filtered activities exclude hardware activities when a tag filter is active`() = test {
        val sut = createViewModel()
        sut.toggleTag("tag1")
        advanceUntilIdle()

        assertEquals(listOf("db1"), sut.filteredActivities.value?.map { it.rawId() })
    }

    @Test
    fun `hardware ids expose the hardware activity ids`() = test {
        val sut = createViewModel()
        val job = launch { sut.hardwareIds.collect {} }
        advanceUntilIdle()

        assertEquals(setOf("hw1"), sut.hardwareIds.value)
        job.cancel()
    }

    @Test
    fun `hardware duplicates of local activities are excluded`() = test {
        hardwareActivities.value = persistentListOf(
            hwActivity,
            onchainActivity(id = "db1", txType = PaymentType.RECEIVED, timestamp = 300uL),
        )
        val sut = createViewModel()
        val job = launch { sut.hardwareIds.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("db1", "hw1"), sut.filteredActivities.value?.map { it.rawId() })
        assertEquals(setOf("hw1"), sut.hardwareIds.value)
        job.cancel()
    }

    private fun onchainActivity(id: String, txType: PaymentType, timestamp: ULong) = Activity.Onchain(
        OnchainActivity.create(
            id = id,
            txType = txType,
            txId = id,
            value = 1_000uL,
            fee = 1uL,
            address = "bc1",
            timestamp = timestamp,
            confirmed = true,
        )
    )
}
