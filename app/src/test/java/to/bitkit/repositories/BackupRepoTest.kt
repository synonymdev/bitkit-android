package to.bitkit.repositories

import android.content.Context
import com.synonym.vssclient.VssItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountBackupSnapshot
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.json
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class BackupRepoTest : BaseUnitTest() {
    private val context = mock<Context>()
    private val cacheStore = mock<CacheStore>()
    private val vssBackupClient = mock<VssBackupClient>()
    private val vssBackupClientLdk = mock<VssBackupClientLdk>()
    private val settingsStore = mock<SettingsStore>()
    private val widgetsStore = mock<WidgetsStore>()
    private val watchOnlyAccountStore = mock<WatchOnlyAccountStore>()
    private val watchOnlyAccountRepo = mock<WatchOnlyAccountRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val paykitSdkService = mock<PaykitSdkService>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val lightningService = mock<LightningService>()
    private val clock = mock<Clock>()
    private val db = mock<AppDb>()
    private val transferDao = mock<TransferDao>()
    private val cacheData = MutableStateFlow(AppCacheData())
    private val settingsData = MutableStateFlow(SettingsData())
    private val widgetsData = MutableStateFlow(WidgetsData())

    private lateinit var sut: BackupRepo

    @Before
    fun setUp() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(1_000))
        whenever(db.transferDao()).thenReturn(transferDao)
        whenever { transferDao.upsert(any<List<TransferEntity>>()) }.thenReturn(Unit)
        whenever { cacheStore.updateBackupStatus(any(), any()) }.thenReturn(Unit)
        whenever { cacheStore.update(any()) }.thenReturn(Unit)
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        whenever(widgetsStore.data).thenReturn(widgetsData)
        whenever(watchOnlyAccountStore.data).thenReturn(MutableStateFlow(WatchOnlyAccountData()))
        whenever { watchOnlyAccountStore.load() }.thenReturn(emptyList())
        whenever { watchOnlyAccountStore.backupSnapshot() }.thenReturn(
            WatchOnlyAccountBackupSnapshot(emptyList(), WatchOnlyAccountAllocationState())
        )
        whenever { vssBackupClient.getObject(any()) }.thenReturn(Result.success(null))
        whenever { vssBackupClient.putObject(any(), any()) }
            .thenReturn(Result.success(VssItem(key = BackupCategory.SETTINGS.name, value = byteArrayOf(), version = 1)))
        whenever { vssBackupClient.setupWithRetry(any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever { vssBackupClientLdk.setup() }.thenReturn(Result.success(Unit))
        whenever { transferDao.getAll() }.thenReturn(emptyList())
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.backupSnapshot() }.thenReturn(Result.success(null))
        whenever { privatePaykitAddressReservationRepo.restoreBackup(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitAddressReservationRepo.backupSnapshot() }.thenReturn(Result.success(null))
        whenever {
            privatePaykitAddressReservationRepo.reconcileReservedIndexesWithLdk()
        }.thenReturn(Result.success(Unit))

        sut = createSut()
    }

    @Test
    fun `full restore should fail when private Paykit reservations fail to restore`() = test {
        stubWalletBackup()
        whenever { privatePaykitAddressReservationRepo.restoreBackup(any()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(privatePaykitRepo, never()).restoreBackup(any())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `start observing backs up stale required status after clearing running flag`() = test {
        val backupStatuses = MutableStateFlow(
            mapOf(
                BackupCategory.WALLET to BackupItemStatus(
                    running = true,
                    synced = 1_000,
                    required = 2_000,
                ),
            )
        )
        val allowWalletClear = CompletableDeferred<Unit>()
        var delayedWalletClear = false
        stubBackupStatuses(backupStatuses, allowWalletClear) {
            delayedWalletClear = true
        }
        stubBackupObservers()

        try {
            sut.startObservingBackups()
            runCurrent()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

            allowWalletClear.complete(Unit)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(delayedWalletClear)
            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `failed automatic backup waits for new required timestamp before retrying`() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(3_000))
        whenever { vssBackupClient.putObject(eq(BackupCategory.SETTINGS.name), any()) }
            .thenReturn(Result.failure(BackupRepoTestError("backup failed")))
        val backupStatuses = MutableStateFlow(
            mapOf(
                BackupCategory.SETTINGS to BackupItemStatus(
                    synced = 1_000,
                    required = 2_000,
                ),
            )
        )
        val allowWalletClear = CompletableDeferred<Unit>().apply { complete(Unit) }
        stubBackupStatuses(backupStatuses, allowWalletClear) {}
        stubBackupObservers()

        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            verify(vssBackupClient).putObject(eq(BackupCategory.SETTINGS.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `failed automatic backup retries newer required timestamp`() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(3_000))
        val backupStatuses = MutableStateFlow(
            mapOf(
                BackupCategory.SETTINGS to BackupItemStatus(
                    synced = 1_000,
                    required = 2_000,
                ),
            )
        )
        var uploadAttempts = 0
        whenever { vssBackupClient.putObject(eq(BackupCategory.SETTINGS.name), any()) }
            .doSuspendableAnswer {
                uploadAttempts += 1
                if (uploadAttempts == 1) {
                    backupStatuses.update { statuses ->
                        val status = statuses.getValue(BackupCategory.SETTINGS)
                        statuses + (BackupCategory.SETTINGS to status.copy(required = 4_000))
                    }
                    whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(5_000))
                    Result.failure(BackupRepoTestError("backup failed"))
                } else {
                    Result.success(
                        VssItem(
                            key = BackupCategory.SETTINGS.name,
                            value = byteArrayOf(),
                            version = 1,
                        )
                    )
                }
            }
        val allowWalletClear = CompletableDeferred<Unit>().apply { complete(Unit) }
        stubBackupStatuses(backupStatuses, allowWalletClear) {}
        stubBackupObservers()

        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            verify(vssBackupClient, times(2)).putObject(eq(BackupCategory.SETTINGS.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `full restore should continue when Paykit SDK state fails to restore`() = test {
        stubWalletBackup()
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verify(settingsStore).update(any())
    }

    @Test
    fun `full restore should continue when backed up Paykit SDK state fails to restore`() = test {
        stubWalletBackup(paykitSdkBackupState = "sdk-state")
        whenever { privatePaykitRepo.restoreBackup("sdk-state") }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verify(settingsStore).update(any())
    }

    @Test
    fun `wallet backup includes watch-only accounts and allocator state`() = test {
        val account = watchOnlyAccount()
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 7),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )
        whenever { watchOnlyAccountStore.backupSnapshot() }.thenReturn(
            WatchOnlyAccountBackupSnapshot(listOf(account), allocationState)
        )
        val dataCaptor = argumentCaptor<ByteArray>()

        sut.triggerBackup(BackupCategory.WALLET)

        verifyBlocking(vssBackupClient) {
            putObject(eq(BackupCategory.WALLET.name), dataCaptor.capture())
        }
        val payload = json.decodeFromString<WalletBackupV1>(dataCaptor.firstValue.decodeToString())
        assertEquals(listOf(account), payload.watchOnlyAccounts)
        assertEquals(allocationState, payload.watchOnlyAccountAllocationState)
    }

    @Test
    fun `wallet restore restores watch-only accounts and allocator before runtime reconciliation`() = test {
        val account = watchOnlyAccount()
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 7),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )
        stubWalletBackup(
            watchOnlyAccounts = listOf(account),
            watchOnlyAccountAllocationState = allocationState,
        )
        var accountsWereRestored = false
        whenever { watchOnlyAccountRepo.restore(listOf(account), allocationState) }.thenAnswer {
            accountsWereRestored = true
            Unit
        }
        whenever { lightningService.reconcileWatchOnlyAccounts() }.thenAnswer {
            assertTrue(accountsWereRestored)
            Unit
        }

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verifyBlocking(watchOnlyAccountRepo) { restore(listOf(account), allocationState) }
        verifyBlocking(lightningService) { reconcileWatchOnlyAccounts() }
    }

    @Test
    fun `full restore should fail when private Paykit reserved indexes fail to reconcile`() = test {
        stubWalletBackup()
        whenever { privatePaykitAddressReservationRepo.reconcileReservedIndexesWithLdk() }
            .thenReturn(Result.failure(BackupRepoTestError("reconcile failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(settingsStore, never()).update(any())
    }

    private fun stubWalletBackup(
        paykitSdkBackupState: String? = null,
        watchOnlyAccounts: List<WatchOnlyAccountRecord>? = null,
        watchOnlyAccountAllocationState: WatchOnlyAccountAllocationState? = null,
    ) {
        val walletBackup = WalletBackupV1(
            createdAt = 123,
            transfers = emptyList(),
            privatePaykitHighestReservedReceiveIndexByAddressType = mapOf("nativeSegwit" to 5),
            paykitSdkBackupState = paykitSdkBackupState,
            watchOnlyAccounts = watchOnlyAccounts,
            watchOnlyAccountAllocationState = watchOnlyAccountAllocationState,
        )
        whenever { vssBackupClient.getObject(BackupCategory.WALLET.name) }
            .thenReturn(
                Result.success(
                    VssItem(
                        key = BackupCategory.WALLET.name,
                        value = json.encodeToString(walletBackup).toByteArray(),
                        version = 1,
                    )
                )
            )
    }

    private fun stubBackupStatuses(
        backupStatuses: MutableStateFlow<Map<BackupCategory, BackupItemStatus>>,
        allowWalletClear: CompletableDeferred<Unit>,
        onWalletClearDelayed: () -> Unit,
    ) {
        whenever(cacheStore.backupStatuses).thenReturn(backupStatuses)
        whenever { cacheStore.updateBackupStatus(any(), any()) }.doSuspendableAnswer {
            val category = it.getArgument<BackupCategory>(0)
            val transform = it.getArgument<(BackupItemStatus) -> BackupItemStatus>(1)
            if (category == BackupCategory.WALLET && !allowWalletClear.isCompleted) {
                onWalletClearDelayed()
                allowWalletClear.await()
            }
            backupStatuses.update { statuses ->
                val currentStatus = statuses[category] ?: BackupItemStatus()
                statuses + (category to transform(currentStatus))
            }
        }
    }

    private fun stubBackupObservers() {
        whenever { transferDao.observeAll() }.thenReturn(MutableStateFlow(emptyList()))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(0L))
        whenever(pubkyRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitSdkService.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(privatePaykitAddressReservationRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(preActivityMetadataRepo.preActivityMetadataChanged).thenReturn(MutableStateFlow(0L))
        whenever(lightningService.syncStatusChanged).thenReturn(MutableSharedFlow())
    }

    private fun createSut() = BackupRepo(
        context = context,
        ioDispatcher = testDispatcher,
        cacheStore = cacheStore,
        vssBackupClient = vssBackupClient,
        vssBackupClientLdk = vssBackupClientLdk,
        settingsStore = settingsStore,
        widgetsStore = widgetsStore,
        watchOnlyAccountStore = watchOnlyAccountStore,
        watchOnlyAccountRepo = watchOnlyAccountRepo,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
        pubkyRepo = pubkyRepo,
        paykitSdkService = paykitSdkService,
        privatePaykitRepo = Provider { privatePaykitRepo },
        privatePaykitAddressReservationRepo = Provider { privatePaykitAddressReservationRepo },
        preActivityMetadataRepo = preActivityMetadataRepo,
        lightningService = lightningService,
        clock = clock,
        db = db,
    )

    private class BackupRepoTestError(message: String) : AppError(message)

    private fun watchOnlyAccount() = WatchOnlyAccountRecord(
        id = "account-7",
        walletIndex = 0,
        accountIndex = 7,
        addressType = "nativeSegwit",
        xpub = "xpub-7",
        requestFingerprint = "pending",
        createdAt = 1,
        name = "Server account",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Authorizing,
    )
}
