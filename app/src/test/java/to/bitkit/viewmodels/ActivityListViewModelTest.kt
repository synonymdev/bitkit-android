package to.bitkit.viewmodels

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.ext.rawId
import to.bitkit.ext.scopedId
import to.bitkit.models.ActivityWalletType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.ActivityState
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityListViewModelTest : BaseUnitTest() {

    private val activityRepo = mock<ActivityRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val settingsStore = mock<SettingsStore>()

    private val bitkitSent = onchainActivity(id = "bitkit-sent", txType = PaymentType.SENT, timestamp = 300uL)
    private val bitkitReceived = onchainActivity(
        id = "bitkit-received",
        txType = PaymentType.RECEIVED,
        timestamp = 250uL,
    )

    private val hwReceived = onchainActivity(
        id = "hw-received",
        txType = PaymentType.RECEIVED,
        timestamp = 200uL,
        walletId = ActivityWalletType.TREZOR.idPrefixed("dev1"),
    )

    private val hwSent = onchainActivity(
        id = "hw-sent",
        txType = PaymentType.SENT,
        timestamp = 150uL,
        walletId = ActivityWalletType.TREZOR.idPrefixed("dev1"),
    )

    private val hwTransfer = onchainActivity(
        id = "hw-transfer",
        txType = PaymentType.SENT,
        timestamp = 175uL,
        walletId = ActivityWalletType.TREZOR.idPrefixed("dev1"),
        isTransfer = true,
    )

    private val hwSecondWallet = onchainActivity(
        id = "hw-second-wallet",
        txType = PaymentType.RECEIVED,
        timestamp = 100uL,
        walletId = ActivityWalletType.TREZOR.idPrefixed("dev2"),
    )

    private val activities = listOf(hwSecondWallet, bitkitSent, hwSent, bitkitReceived, hwReceived, hwTransfer)

    @Before
    fun setUp() {
        whenever(activityRepo.state).thenReturn(MutableStateFlow(ActivityState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(0L))
        whenever { activityRepo.syncActivities() }.thenReturn(Result.success(Unit))
        whenever { activityRepo.getTxIdsInBoostTxIds(any()) }.thenReturn(emptySet())
        whenever {
            activityRepo.getActivities(
                walletId = anyOrNull(),
                filter = anyOrNull(),
                txType = anyOrNull(),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = anyOrNull(),
                sortDirection = anyOrNull(),
            )
        }.thenReturn(Result.success(activities))
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(settingsStore.isPaykitEnabled).thenReturn(MutableStateFlow(false))
    }

    private fun createViewModel() = ActivityListViewModel(
        bgDispatcher = testDispatcher,
        activityRepo = activityRepo,
        pubkyRepo = pubkyRepo,
        settingsStore = settingsStore,
    )

    @Test
    fun `filtered activities include hardware activities newest first`() = test {
        val sut = createViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf("bitkit-sent", "bitkit-received", "hw-received", "hw-transfer", "hw-sent", "hw-second-wallet"),
            sut.filteredActivities.value?.map { it.rawId() },
        )
    }

    @Test
    fun `filtered activities exclude hardware activities not matching the tab`() = test {
        whenever {
            activityRepo.getActivities(
                walletId = anyOrNull(),
                filter = anyOrNull(),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = anyOrNull(),
                sortDirection = anyOrNull(),
            )
        }.thenReturn(Result.success(listOf(bitkitSent, hwSent, hwTransfer)))
        val sut = createViewModel()
        sut.setTab(ActivityTab.SENT)
        advanceUntilIdle()

        assertEquals(listOf("bitkit-sent", "hw-sent"), sut.filteredActivities.value?.map { it.rawId() })
    }

    @Test
    fun `hardware transfer is included only under the other tab`() = test {
        val sut = createViewModel()
        sut.setTab(ActivityTab.OTHER)
        advanceUntilIdle()

        assertEquals(listOf("hw-transfer"), sut.filteredActivities.value?.map { it.rawId() })
    }

    @Test
    fun `hardware activity is included under an active tag filter`() = test {
        whenever {
            activityRepo.getActivities(
                walletId = anyOrNull(),
                filter = anyOrNull(),
                txType = anyOrNull(),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = anyOrNull(),
                sortDirection = anyOrNull(),
            )
        }.thenReturn(Result.success(listOf(hwReceived, hwSent, hwSecondWallet)))
        val sut = createViewModel()
        sut.toggleTag("tag1")
        advanceUntilIdle()

        assertEquals(
            listOf("hw-received", "hw-sent", "hw-second-wallet"),
            sut.filteredActivities.value?.map { it.rawId() },
        )
    }

    @Test
    fun `hardware ids expose the ids of activities scoped to a hardware wallet`() = test {
        val sut = createViewModel()
        val job = launch { sut.hardwareIds.collect {} }
        advanceUntilIdle()

        assertEquals(
            setOf(hwReceived.scopedId(), hwTransfer.scopedId(), hwSent.scopedId(), hwSecondWallet.scopedId()),
            sut.hardwareIds.value,
        )
        job.cancel()
    }

    @Test
    fun `onchain activities remain scoped to Bitkit wallet`() = test {
        val sut = createViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf("bitkit-sent", "bitkit-received"),
            sut.onchainActivities.value?.map { it.rawId() },
        )
    }

    @Test
    fun `replaced transactions remain scoped by wallet`() = test {
        val txId = "shared-tx"
        val hardwareWalletId = ActivityWalletType.TREZOR.idPrefixed("dev1")
        val bitkit = Activity.Onchain(
            onchainActivity(
                id = "bitkit-replaced",
                txType = PaymentType.SENT,
                timestamp = 200uL,
            ).v1.copy(
                txId = txId,
                doesExist = false,
            ),
        )
        val hardware = Activity.Onchain(
            onchainActivity(
                id = "hardware-replaced",
                txType = PaymentType.SENT,
                timestamp = 100uL,
                walletId = hardwareWalletId,
            ).v1.copy(
                txId = txId,
                doesExist = false,
            ),
        )
        whenever {
            activityRepo.getActivities(
                walletId = anyOrNull(),
                filter = anyOrNull(),
                txType = anyOrNull(),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = anyOrNull(),
                sortDirection = anyOrNull(),
            )
        }.thenReturn(Result.success(listOf(bitkit, hardware)))
        whenever { activityRepo.getTxIdsInBoostTxIds(ActivityWalletType.BITKIT.id()) }.thenReturn(setOf(txId))
        whenever { activityRepo.getTxIdsInBoostTxIds(hardwareWalletId) }.thenReturn(emptySet())

        val sut = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("hardware-replaced"), sut.filteredActivities.value?.map { it.rawId() })
    }

    private fun onchainActivity(
        id: String,
        txType: PaymentType,
        timestamp: ULong,
        walletId: String = ActivityWalletType.BITKIT.id(),
        isTransfer: Boolean = false,
    ) = Activity.Onchain(
        OnchainActivity.create(
            id = id,
            txType = txType,
            txId = id,
            value = 1_000uL,
            fee = 1uL,
            address = "bc1",
            timestamp = timestamp,
            confirmed = true,
            walletId = walletId,
            isTransfer = isTransfer,
        )
    )
}
