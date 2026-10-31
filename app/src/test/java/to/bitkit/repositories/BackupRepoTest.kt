package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.paykit.PaykitException
import com.synonym.vssclient.VssItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.AppCacheData
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.HwWalletData
import to.bitkit.data.HwWalletStore
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountBackupSnapshot
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.UsdtRecoveryBackupStore
import to.bitkit.data.backup.UsdtRecoveryBackupV1
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.withPublicSharingDisabledForRestore
import to.bitkit.di.json
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.KnownDevice
import to.bitkit.models.MetadataBackupV1
import to.bitkit.models.PaykitAllowanceStateBackup
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.PaykitUsdtStateBackup
import to.bitkit.models.SettingsBackupV1
import to.bitkit.models.TransportType
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.LightningService
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.utils.AppError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    private val hwWalletStore = mock<HwWalletStore>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val coreService = mock<to.bitkit.services.CoreService>()
    private val lightningRepo = mock<LightningRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val paykitSdkService = mock<PaykitSdkService>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val paykitPaymentProofRepo = mock<PaykitPaymentProofRepo>()
    private val usdtRepo = mock<UsdtRepo>()
    private val paykitUsdtPaymentRepo = mock<PaykitUsdtPaymentRepo>()
    private val paykitPaymentProofStore = mock<PaykitPaymentProofStore>()
    private val paykitPaymentRequestRepo = mock<PaykitPaymentRequestRepo>()
    private val paykitPresentationStore = mock<PaykitPaymentRequestPresentationStore>()
    private val paykitAllowanceStore = mock<PaykitAllowanceStore>()
    private val keychain = mock<Keychain>()
    private val onchainSendAttemptStore = mock<OnchainSendAttemptStore>()
    private val vssStoreIdProvider = mock<to.bitkit.data.backup.VssStoreIdProvider>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val lightningService = mock<LightningService>()
    private val clock = mock<Clock>()
    private val db = mock<AppDb>()
    private val transferDao = mock<TransferDao>()
    private val cacheData = MutableStateFlow(AppCacheData())
    private val settingsData = MutableStateFlow(SettingsData())
    private val widgetsData = MutableStateFlow(WidgetsData())
    private val hwWalletData = MutableStateFlow(HwWalletData())

    private val paymentSubmissionActive = MutableStateFlow(false)

    private lateinit var sut: BackupRepo

    @Before
    fun setUp() = test {
        whenever(coreService.validateBitcoinAddress(any())).thenAnswer {
            com.synonym.bitkitcore.ValidationResult(
                address = it.getArgument(0),
                network = com.synonym.bitkitcore.NetworkType.REGTEST,
                addressType = com.synonym.bitkitcore.AddressType.P2WPKH,
            )
        }
        whenever(paykitSdkService.isPaymentSubmissionActive).thenReturn(paymentSubmissionActive)
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(1_000))
        whenever(db.transferDao()).thenReturn(transferDao)
        whenever(db.configDao()).thenReturn(mock())
        whenever(db.configDao().getAll()).thenReturn(MutableStateFlow(listOf(to.bitkit.data.entities.ConfigEntity())))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(null))
        whenever(onchainSendAttemptStore.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(onchainSendAttemptStore.backupSnapshot(eq(0), any())).doSuspendableAnswer {
            val capture = it.getArgument<suspend () -> List<to.bitkit.models.PaykitPaymentStateBackup.Proof>>(1)
            onchainSendAttemptStore.backupSnapshot(0) to capture()
        }
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
        whenever(hwWalletStore.data).thenReturn(hwWalletData)
        whenever { hwWalletStore.backupSnapshot() }.thenReturn(emptyMap())
        whenever { hwWalletStore.restoreNames(any()) }.thenReturn(Unit)
        whenever { vssBackupClient.getObject(any()) }.thenReturn(Result.success(null))
        whenever { vssBackupClient.putObject(any(), any()) }
            .thenReturn(Result.success(VssItem(key = BackupCategory.SETTINGS.name, value = byteArrayOf(), version = 1)))
        whenever { vssBackupClient.setupWithRetry(any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever { vssBackupClient.setup(any()) }.thenReturn(Result.success(Unit))
        whenever { vssBackupClientLdk.setup() }.thenReturn(Result.success(Unit))
        whenever { transferDao.getAll() }.thenReturn(emptyList())
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.backupSnapshot() }.thenReturn(Result.success(PrivatePaykitBackupSnapshot(null, 0)))
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitSdkService.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever { paykitPaymentProofRepo.backupSnapshot() }.thenReturn(emptyList())
        whenever { paykitUsdtPaymentRepo.backupSnapshot() }.thenReturn(
            PaykitUsdtStateBackup(emptyList(), emptyList())
        )
        whenever(usdtRepo.state).thenReturn(MutableStateFlow(UsdtWalletState()))
        whenever(paykitUsdtPaymentRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitPresentationStore.backupSnapshot()).thenReturn(emptyMap())
        whenever(paykitAllowanceStore.backupSnapshot()).thenReturn(emptyMap())
        whenever(privatePaykitAddressReservationRepo.preparePublicAddressRestore()).thenReturn(false)
        whenever { privatePaykitAddressReservationRepo.restoreBackup(anyOrNull(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitAddressReservationRepo.quarantinedPublicAddressesSnapshot() }.thenReturn(emptySet())
        whenever { privatePaykitAddressReservationRepo.backupSnapshot() }.thenReturn(Result.success(null))
        whenever(privatePaykitAddressReservationRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever {
            privatePaykitAddressReservationRepo.reconcileReservedIndexesWithLdk()
        }.thenReturn(Result.success(Unit))

        whenever { vssBackupClient.listKeys() }.thenReturn(Result.success(emptyList()))
        sut = createSut()
    }

    @Test
    fun `restore marker storage reads leave Main responsive`() = test {
        val entered = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        val mainThread = Thread.currentThread()
        whenever(keychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)).thenAnswer {
            entered.complete(Thread.currentThread())
            check(release.await(5, TimeUnit.SECONDS)) { "Marker read was not released" }
            false
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ioDispatcher ->
            sut = createSut(ioDispatcher = ioDispatcher)
            try {
                val read = async(Dispatchers.Main) { sut.hasPendingWalletRestore() }
                val storageThread = withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) { entered.await() }
                }
                assertNotEquals(mainThread, storageThread)
                assertTrue(async(Dispatchers.Main) { true }.await())
                assertFalse(read.isCompleted)

                release.countDown()

                assertFalse(read.await())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `wallet backup waits for submission and includes the latest pending state`() = test {
        val statuses = MutableStateFlow(
            mapOf(BackupCategory.WALLET to BackupItemStatus(required = 1_000)),
        )
        val allowClear = CompletableDeferred(Unit)
        stubBackupStatuses(statuses, allowClear) {}
        stubBackupObservers()
        val proofVersion = MutableStateFlow(0L)
        whenever(paykitPaymentProofStore.backupStateVersion).thenReturn(proofVersion)
        paymentSubmissionActive.value = true
        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            proofVersion.value++
            runCurrent()
            proofVersion.value++
            runCurrent()

            assertTrue(statuses.value.getValue(BackupCategory.WALLET).isRequired)
            verify(privatePaykitRepo, never()).backupSnapshot()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

            whenever(privatePaykitRepo.backupSnapshot()).thenReturn(
                Result.success(PrivatePaykitBackupSnapshot("pending-write", 0)),
            )
            paymentSubmissionActive.value = false
            runCurrent()

            val payload = argumentCaptor<ByteArray>()
            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), payload.capture())
            assertEquals(
                "pending-write",
                json.decodeFromString<WalletBackupV1>(String(payload.firstValue)).paykitSdkBackupState,
            )
            verify(paykitPaymentProofRepo).backupSnapshot()
            verify(privatePaykitRepo).backupSnapshot()
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `cancelled manual backup completes success and failure status writes`() = test {
        for (uploadSucceeds in listOf(true, false)) {
            val category = BackupCategory.SETTINGS
            val statuses = MutableStateFlow(mapOf(category to BackupItemStatus(required = 1_000)))
            val statusWriteStarted = CompletableDeferred<Unit>()
            val finishStatusWrite = CompletableDeferred<Unit>()
            whenever(cacheStore.backupStatuses).thenReturn(statuses)
            whenever(cacheStore.updateBackupStatus(eq(category), any())).doSuspendableAnswer {
                val transform = it.getArgument<(BackupItemStatus) -> BackupItemStatus>(1)
                val updated = transform(statuses.value.getValue(category))
                if (!updated.running) {
                    statusWriteStarted.complete(Unit)
                    finishStatusWrite.await()
                }
                statuses.update { current -> current + (category to updated) }
            }
            val uploadResult = if (uploadSucceeds) {
                Result.success(VssItem(key = category.name, value = byteArrayOf(), version = 1))
            } else {
                Result.failure(BackupRepoTestError("upload failed"))
            }
            whenever(vssBackupClient.putObject(eq(category.name), any())).thenReturn(uploadResult)

            val backup = launch { sut.triggerBackup(category) }
            runCurrent()
            assertTrue(statusWriteStarted.isCompleted)
            assertTrue(statuses.value.getValue(category).running)

            backup.cancel()
            runCurrent()
            assertFalse(backup.isCompleted)
            finishStatusWrite.complete(Unit)
            backup.join()

            val status = statuses.value.getValue(category)
            assertTrue(backup.isCancelled)
            assertFalse(status.running)
            assertEquals(if (uploadSucceeds) status.required else 0L, status.synced)
            assertEquals(!uploadSucceeds, status.isRequired)
        }
    }

    @Test
    fun `cancelled manual wallet backup retains pending state and resumes automatically`() = test {
        val statuses = MutableStateFlow(
            mapOf(BackupCategory.WALLET to BackupItemStatus(required = 1_000)),
        )
        stubBackupStatuses(statuses, CompletableDeferred(Unit)) {}
        stubBackupObservers()
        paymentSubmissionActive.value = true
        whenever(privatePaykitRepo.backupSnapshot()).doSuspendableAnswer {
            paymentSubmissionActive.first { !it }
            Result.success(PrivatePaykitBackupSnapshot("pending-write", 0))
        }

        val backup = launch { sut.triggerBackup(BackupCategory.WALLET) }
        runCurrent()
        verify(privatePaykitRepo).backupSnapshot()
        assertTrue(statuses.value.getValue(BackupCategory.WALLET).running)

        backup.cancelAndJoin()

        assertTrue(backup.isCancelled)
        assertFalse(statuses.value.getValue(BackupCategory.WALLET).running)
        assertTrue(statuses.value.getValue(BackupCategory.WALLET).isRequired)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

        try {
            sut.startObservingBackups()
            paymentSubmissionActive.value = false
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            val payload = argumentCaptor<ByteArray>()
            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), payload.capture())
            assertEquals(
                "pending-write",
                json.decodeFromString<WalletBackupV1>(String(payload.firstValue)).paykitSdkBackupState,
            )
            assertFalse(statuses.value.getValue(BackupCategory.WALLET).running)
            assertFalse(statuses.value.getValue(BackupCategory.WALLET).isRequired)
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `restored accepted Blocktank funding finishes through the ordinary follow-up`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val originalWire = requireNotNull(state.activeOnchainAttempt)
        val original = originalWire.restored("regtest", originalWire.wallet.binding, WalletScope.default, 0)
        val funding = original.copy(
            requestId = null,
            payerIdentity = null,
            orderId = "original-order",
            amountSats = 1_000uL,
            isTransfer = true,
            evidence = OnchainSendEvidence.Accepted,
            candidateFeeRates = mapOf(requireNotNull(original.txid) to 4uL),
            transferContext = OnchainTransferContext(2_000uL, 3_000uL, 900uL, 1_000uL),
            backupFollowup = requireNotNull(original.backupFollowup).copy(contact = null),
        )
        val wire = to.bitkit.models.ActiveOnchainAttemptBackup.from(funding, "regtest", originalWire.wallet.binding)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(originalWire.wallet.binding)
        whenever(onchainSendAttemptStore.current()).thenReturn(funding)
        stubWalletBackup(paykitPaymentState = state.copy(pendingProofs = emptyList(), activeOnchainAttempt = wire))
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        val ordered = org.mockito.kotlin.inOrder(onchainSendAttemptStore, transferDao, privatePaykitRepo, lightningRepo)
        ordered.verify(onchainSendAttemptStore).restoreActive(
            wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0)
        )
        ordered.verify(transferDao).upsert(any<List<TransferEntity>>())
        ordered.verify(privatePaykitRepo).restoreBackup(anyOrNull())
        ordered.verify(lightningRepo).completeAcceptedOrdinaryFollowup(requireNotNull(funding.txid))
        whenever(onchainSendAttemptStore.current()).thenReturn(funding.copy(evidence = OnchainSendEvidence.Unknown))
        stubWalletBackup(
            paykitPaymentState = state.copy(
                pendingProofs = emptyList(),
                activeOnchainAttempt = wire.copy(status = "unknown")
            )
        )
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        verify(lightningRepo, times(1)).completeAcceptedOrdinaryFollowup(any())
    }

    @Test
    fun `restored accepted ordinary payment resumes after original context without another node event`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val originalWire = requireNotNull(state.activeOnchainAttempt)
        val original = originalWire.restored("regtest", originalWire.wallet.binding, WalletScope.default, 0)
        val ordinary = original.copy(
            requestId = null,
            payerIdentity = null,
            orderId = null,
            isTransfer = false,
            evidence = OnchainSendEvidence.Accepted,
            candidateFeeRates = mapOf(requireNotNull(original.txid) to 4uL),
        )
        val wire = to.bitkit.models.ActiveOnchainAttemptBackup.from(ordinary, "regtest", originalWire.wallet.binding)
        whenever(onchainSendAttemptStore.current()).thenReturn(ordinary)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(originalWire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(pendingProofs = emptyList(), activeOnchainAttempt = wire))
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        val ordered = org.mockito.kotlin.inOrder(onchainSendAttemptStore, privatePaykitRepo, lightningRepo)
        ordered.verify(
            onchainSendAttemptStore
        ).restoreActive(wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0))
        ordered.verify(privatePaykitRepo).restoreBackup(anyOrNull())
        ordered.verify(lightningRepo).completeAcceptedOrdinaryFollowup(requireNotNull(ordinary.txid))
        whenever(onchainSendAttemptStore.current()).thenReturn(ordinary.copy(evidence = OnchainSendEvidence.Unknown))
        stubWalletBackup(
            paykitPaymentState = state.copy(
                pendingProofs = emptyList(),
                activeOnchainAttempt = wire.copy(status = "unknown")
            )
        )
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        verify(lightningRepo, times(1)).completeAcceptedOrdinaryFollowup(any())
    }

    @Test
    fun `older unresolved backup resumes accepted merged ordinary guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val originalWire = requireNotNull(state.activeOnchainAttempt)
        val wire = originalWire.copy(requestId = null, payerIdentity = null)
        val downloaded = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0)
        val merged = downloaded.copy(
            evidence = OnchainSendEvidence.Accepted,
            candidateFeeRates = mapOf(requireNotNull(downloaded.txid) to 4uL),
        )
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(onchainSendAttemptStore.current()).thenReturn(merged)
        stubWalletBackup(paykitPaymentState = state.copy(pendingProofs = emptyList(), activeOnchainAttempt = wire))

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        val ordered = org.mockito.kotlin.inOrder(onchainSendAttemptStore, privatePaykitRepo, lightningRepo)
        ordered.verify(onchainSendAttemptStore).restoreActive(downloaded)
        ordered.verify(privatePaykitRepo).restoreBackup(anyOrNull())
        ordered.verify(lightningRepo).completeAcceptedOrdinaryFollowup(requireNotNull(merged.txid))
    }

    @Test
    fun `wallet backup captures transfer completed before locked attempt snapshot`() = test {
        val transfer = TransferEntity(
            id = "original-transfer",
            type = to.bitkit.models.TransferType.TO_SPENDING,
            amountSats = 1000,
            fundingTxId = "ab".repeat(32),
            createdAt = 1,
        )
        var savedTransfers = emptyList<TransferEntity>()
        whenever(transferDao.getAll()).thenAnswer { savedTransfers }
        whenever(onchainSendAttemptStore.backupSnapshot(eq(0), any())).doSuspendableAnswer {
            // Accepted follow-up commits its transfer and completes the guard before this lock is acquired.
            savedTransfers = listOf(transfer)
            val capture = it.getArgument<suspend () -> List<PaykitPaymentStateBackup.Proof>>(1)
            null to capture()
        }
        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()
        val data = argumentCaptor<ByteArray>()
        verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.WALLET.name), data.capture()) }
        val payload = json.decodeFromString<WalletBackupV1>(data.firstValue.decodeToString())
        assertEquals(listOf(transfer), payload.transfers)
    }

    @Test
    fun `wallet backup rejects private consumption changed during proof capture`() = test {
        val version = MutableStateFlow(0L)
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(version)
        whenever { onchainSendAttemptStore.backupSnapshot(any(), any()) }.doSuspendableAnswer {
            version.update { current -> current + 1 }
            val capture = it.getArgument<suspend () -> List<PaykitPaymentStateBackup.Proof>>(1)
            null to capture()
        }
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        verify(privatePaykitRepo, times(3)).backupSnapshot()
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `wallet backup recaptures after private state changes without uploading the discarded snapshot`() = test {
        val version = MutableStateFlow(0L)
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(version)
        whenever(privatePaykitRepo.backupSnapshot()).doSuspendableAnswer {
            Result.success(PrivatePaykitBackupSnapshot("private-state-${version.value}", 0))
        }
        var captures = 0
        whenever(onchainSendAttemptStore.backupSnapshot(eq(0), any())).doSuspendableAnswer {
            captures++
            if (captures == 1) version.update { current -> current + 1 }
            val capture = it.getArgument<suspend () -> List<PaykitPaymentStateBackup.Proof>>(1)
            null to capture()
        }

        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()

        assertEquals(2, captures)
        val payload = argumentCaptor<ByteArray>()
        verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), payload.capture())
        val backup = json.decodeFromString<WalletBackupV1>(payload.firstValue.decodeToString())
        assertEquals("private-state-1", backup.paykitSdkBackupState)
    }

    @Test
    fun `wallet backup accepts SDK changes included in its queued export`() = test {
        val sdkVersion = MutableStateFlow(0L)
        whenever(paykitSdkService.backupStateVersion).thenReturn(sdkVersion)
        whenever(privatePaykitRepo.backupSnapshot()).doSuspendableAnswer {
            sdkVersion.update { current -> current + 1 }
            Result.success(PrivatePaykitBackupSnapshot("latest-sdk-state", sdkVersion.value))
        }

        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()

        val payload = argumentCaptor<ByteArray>()
        verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), payload.capture())
        val backup = json.decodeFromString<WalletBackupV1>(payload.firstValue.decodeToString())
        assertEquals("latest-sdk-state", backup.paykitSdkBackupState)
        verify(privatePaykitRepo).backupSnapshot()
    }

    @Test
    fun `wallet backup checks SDK and reservation changes through the last capture`() = test {
        val sdkVersion = MutableStateFlow(0L)
        val reservationVersion = MutableStateFlow(0L)
        whenever(paykitSdkService.backupStateVersion).thenReturn(sdkVersion)
        whenever(privatePaykitAddressReservationRepo.backupStateVersion).thenReturn(reservationVersion)
        for (version in listOf(sdkVersion, reservationVersion)) {
            whenever(privatePaykitRepo.backupSnapshot()).doSuspendableAnswer {
                Result.success(PrivatePaykitBackupSnapshot("sdk-${sdkVersion.value}", sdkVersion.value))
            }
            whenever(paykitUsdtPaymentRepo.backupSnapshot()).thenAnswer {
                version.update { current -> current + 1 }
                PaykitUsdtStateBackup(emptyList(), emptyList())
            }

            assertTrue(sut.triggerBackup(BackupCategory.WALLET).exceptionOrNull() is WalletBackupSnapshotChanged)
        }
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `wallet backup rejects identity replacement between snapshot attempts`() = test {
        var generation = 0L
        val version = MutableStateFlow(0L)
        var versionReads = 0
        whenever(paykitSdkService.runtimeGeneration).thenAnswer { generation }
        whenever(privatePaykitRepo.backupStateVersion).thenAnswer {
            // Replace the identity after its final check, as the first snapshot is discarded.
            if (++versionReads == 2) generation++
            version
        }
        whenever(onchainSendAttemptStore.backupSnapshot(eq(0), any())).doSuspendableAnswer {
            version.update { current -> current + 1 }
            val capture = it.getArgument<suspend () -> List<PaykitPaymentStateBackup.Proof>>(1)
            null to capture()
        }

        val error = sut.triggerBackup(BackupCategory.WALLET).exceptionOrNull()

        assertEquals("Paykit identity changed during wallet snapshot", error?.message)
        verify(privatePaykitRepo).backupSnapshot()
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `wallet backup rejects identity replacement without retrying into the new identity`() = test {
        var generation = 0L
        whenever(paykitSdkService.runtimeGeneration).thenAnswer { generation }
        whenever(privatePaykitRepo.backupSnapshot()).doSuspendableAnswer {
            generation++
            Result.success(PrivatePaykitBackupSnapshot("replacement", 0))
        }

        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)

        verify(privatePaykitRepo).backupSnapshot()
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `automatic backup retries exhausted contention after state becomes quiet`() = test {
        val statuses = MutableStateFlow(mapOf(BackupCategory.WALLET to BackupItemStatus(required = 1_000)))
        stubBackupStatuses(statuses, CompletableDeferred(Unit)) {}
        stubBackupObservers()
        val version = MutableStateFlow(0L)
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(version)
        var collisions = 3
        whenever(onchainSendAttemptStore.backupSnapshot(eq(0), any())).doSuspendableAnswer {
            if (collisions-- > 0) version.update { current -> current + 1 }
            val capture = it.getArgument<suspend () -> List<PaykitPaymentStateBackup.Proof>>(1)
            null to capture()
        }
        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
            assertTrue(statuses.value.getValue(BackupCategory.WALLET).isRequired)

            advanceTimeBy(5_000)
            runCurrent()

            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), any())
            assertFalse(statuses.value.getValue(BackupCategory.WALLET).isRequired)
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `automatic backup reschedules snapshot contention without a new required revision`() = test {
        val statuses = MutableStateFlow(mapOf(BackupCategory.WALLET to BackupItemStatus(required = 1_000)))
        stubBackupStatuses(statuses, CompletableDeferred(Unit)) {}
        stubBackupObservers()
        whenever(privatePaykitRepo.backupSnapshot()).thenReturn(
            Result.failure(WalletBackupSnapshotChanged()),
            Result.failure(WalletBackupSnapshotChanged()),
            Result.failure(WalletBackupSnapshotChanged()),
            Result.success(PrivatePaykitBackupSnapshot(null, 0)),
        )
        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            verify(privatePaykitRepo, times(3)).backupSnapshot()
            assertEquals(1_000, statuses.value.getValue(BackupCategory.WALLET).required)
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

            advanceTimeBy(5_000)
            runCurrent()

            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), any())
            assertFalse(statuses.value.getValue(BackupCategory.WALLET).isRequired)
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `restore brings back each identity's allowance journal and history`() = test {
        val identity = "pubky" + "z".repeat(52)
        val allowances = PaykitAllowanceStateBackup(
            journal = listOf(
                PaykitAllowanceStateBackup.JournalEntry(
                    attemptId = "attempt-1",
                    isAutomatic = true,
                    requestId = PaykitPaymentRequestId("request-1", "pubky" + "y".repeat(52)),
                    allowanceId = "allowance-1",
                    amountSats = 1_000uL,
                    paymentEndpointIdentifier = "btc-lightning-bolt11",
                    paymentHash = "ab".repeat(32),
                    stage = "sent",
                    createdAt = "2026-10-27T01:00:00Z",
                ),
            ),
            autoPaidSatsByAllowance = mapOf("pubky${"y".repeat(52)}/allowance-1" to 3_000uL),
        )
        stubWalletBackup(
            paykitPaymentState = PaykitPaymentStateBackup(
                subscriptions = emptyMap(),
                pendingProofs = emptyList(),
                allowances = mapOf(identity to allowances),
            ),
        )

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        verify(paykitAllowanceStore).restoreBackup(mapOf(identity to allowances.restored()))
    }

    @Test
    fun `restore without Paykit state drops the ledger floor`() = test {
        val identity = "pubky" + "z".repeat(52)
        val allowances = PaykitAllowanceStateBackup(ledgerRevisionFloor = 5uL, endedAllowanceIds = setOf("ended"))
        stubWalletBackup(
            paykitSdkBackupState = null,
            paykitPaymentState = PaykitPaymentStateBackup(
                subscriptions = emptyMap(),
                pendingProofs = emptyList(),
                allowances = mapOf(identity to allowances),
            ),
        )

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        val captor = argumentCaptor<Map<String, PaykitAllowanceLocalState>>()
        verify(paykitAllowanceStore).restoreBackup(captor.capture())
        val restored = captor.firstValue.getValue(identity)
        assertNull(restored.ledgerRevisionFloor)
        assertEquals(setOf("ended"), restored.endedAllowanceIds)
    }

    @Test
    fun `restore with Paykit state keeps the ledger floor`() = test {
        val identity = "pubky" + "z".repeat(52)
        val allowances = PaykitAllowanceStateBackup(ledgerRevisionFloor = 5uL)
        stubWalletBackup(
            paykitSdkBackupState = "saved-sdk-state",
            paykitPaymentState = PaykitPaymentStateBackup(
                subscriptions = emptyMap(),
                pendingProofs = emptyList(),
                allowances = mapOf(identity to allowances),
            ),
        )

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        verify(paykitAllowanceStore).restoreBackup(mapOf(identity to allowances.restored()))
    }

    @Test
    fun `restored accepted Shop reconciles after proof and private state`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val originalWire = requireNotNull(state.activeOnchainAttempt)
        val wire = originalWire.copy(
            status = "accepted",
            candidateFeeRates = mapOf(requireNotNull(originalWire.txid) to "4")
        )
        whenever(
            onchainSendAttemptStore.current()
        ).thenReturn(wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0))
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(activeOnchainAttempt = wire))
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        val ordered = org.mockito.kotlin.inOrder(paykitPaymentProofRepo, privatePaykitRepo)
        ordered.verify(paykitPaymentProofRepo).restoreBackup(state.pendingProofs)
        ordered.verify(privatePaykitRepo).restoreBackup(anyOrNull())
        ordered.verify(paykitPaymentProofRepo).reconcile()
    }

    @Test
    fun `wallet backup defers the entire never prepared Shop snapshot`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        val attempt = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0).copy(
            evidence = OnchainSendEvidence.Pending,
            txid = null,
            originalInputs = null,
            candidateTxids = emptyList(),
            candidateFeeRates = emptyMap(),
            preparationPending = true,
            restoredFromBackup = false,
        )
        val original = state.pendingProofs.single().copy(paymentIdentifier = null)
        val unrelated = original.copy(paymentAppId = "unrelated-app", identity = "other-payer")
        whenever(onchainSendAttemptStore.backupSnapshot(0)).thenReturn(attempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(paykitPaymentProofRepo.backupSnapshot()).thenReturn(listOf(original, unrelated))
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `wallet backup defers unsigned ordinary and transfer snapshots`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        val attempt = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0).copy(
            evidence = OnchainSendEvidence.Pending,
            txid = null,
            originalInputs = null,
            candidateTxids = emptyList(),
            candidateFeeRates = emptyMap(),
            preparationPending = true,
            restoredFromBackup = false,
        )
        val unsigned = attempt.copy(requestId = null, preparationPending = false)
        val original = state.pendingProofs.single().copy(paymentIdentifier = null)
        val unrelated = original.copy(paymentAppId = "unrelated-app", identity = "other-payer")
        whenever(onchainSendAttemptStore.backupSnapshot(0)).thenReturn(unsigned)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(paykitPaymentProofRepo.backupSnapshot()).thenReturn(listOf(original, unrelated))
        for (transfer in listOf(false, true)) {
            whenever(onchainSendAttemptStore.backupSnapshot(0)).thenReturn(unsigned.copy(isTransfer = transfer))
            assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        }
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `wallet backup exports and restores shared active guard before original proof`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val golden = json.decodeFromString<WalletBackupV1>(bytes.decodeToString())
        val state = requireNotNull(golden.paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        val attempt = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0)
        whenever(onchainSendAttemptStore.backupSnapshot(0)).thenReturn(attempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(paykitPaymentProofRepo.backupSnapshot()).thenReturn(state.pendingProofs)
        val data = argumentCaptor<ByteArray>()
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isSuccess)
        verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.WALLET.name), data.capture()) }
        val exported = json.decodeFromString<WalletBackupV1>(data.firstValue.decodeToString())
        assertEquals(wire, exported.paykitPaymentState?.activeOnchainAttempt)
        stubWalletBackup(paykitPaymentState = state)
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        val ordered = org.mockito.kotlin.inOrder(onchainSendAttemptStore, paykitPaymentProofRepo)
        ordered.verify(onchainSendAttemptStore).restoreActive(attempt)
        ordered.verify(paykitPaymentProofRepo).restoreBackup(state.pendingProofs)
    }

    @Test
    fun `invalid unrelated proof fails restore before installing the guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        val invalid = state.pendingProofs.first().copy(
            requestId = state.pendingProofs.first().requestId.copy(
                paymentRequestId = "550e8400-e29b-41d4-a716-446655440099"
            ),
            kind = "unsupported-proof",
        )
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(pendingProofs = state.pendingProofs + invalid))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `active guard uses captured backup namespace index instead of assuming index zero`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(db.configDao().getAll()).thenReturn(MutableStateFlow(listOf(to.bitkit.data.entities.ConfigEntity(2))))
        whenever(vssStoreIdProvider.getBackupWalletBinding(2)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state)
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        verify(
            onchainSendAttemptStore
        ).restoreActive(wire.restored("regtest", wire.wallet.binding, WalletScope.default, 2))
        verify(
            onchainSendAttemptStore,
            never()
        ).restoreActive(wire.restored("regtest", wire.wallet.binding, WalletScope.default, 0))
    }

    @Test
    fun `conflicting original fee fails restore before installing the guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        val conflicting = requireNotNull(wire.candidateFeeRates) + (wire.candidateTxids.first() to "3")
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(
            paykitPaymentState = state.copy(activeOnchainAttempt = wire.copy(candidateFeeRates = conflicting))
        )
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `mismatched funding amount fails restore before installing the guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val original = requireNotNull(state.activeOnchainAttempt)
        val wire = original.copy(
            requestId = null,
            payerIdentity = null,
            orderId = "original-order",
            transfer = to.bitkit.models.ActiveOnchainAttemptBackup.Transfer("2000", "3000", "900", "1000"),
        )
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(pendingProofs = emptyList(), activeOnchainAttempt = wire))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(lightningRepo, never()).completeAcceptedOrdinaryFollowup(any())
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `missing successor fee blocks restore before installing the guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(activeOnchainAttempt = wire.copy(candidateFeeRates = null)))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `malformed active contact fails restore before installing the guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(
            paykitPaymentState = state.copy(
                activeOnchainAttempt = wire.copy(
                    followup = requireNotNull(wire.followup).copy(
                        contact = kotlinx.serialization.json.JsonPrimitive(123),
                    ),
                )
            )
        )
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `wrong active binding or proof blocks restore without clearing guard or publishing empty backup`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn("00".repeat(32))
        stubWalletBackup(paykitPaymentState = state)
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(
            paykitPaymentState = state.copy(
                pendingProofs = state.pendingProofs.map {
                    it.copy(onchainWalletId = "foreign-hardware")
                }
            )
        )
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `invalid restored recipient cannot install a blocking wallet guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(
            coreService.validateBitcoinAddress(wire.address)
        ).thenThrow(IllegalArgumentException("invalid address"))
        stubWalletBackup(paykitPaymentState = state)
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
    }

    @Test
    fun `wrong network restored recipient cannot install a blocking wallet guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        whenever(coreService.validateBitcoinAddress(wire.address)).thenReturn(
            com.synonym.bitkitcore.ValidationResult(
                wire.address,
                com.synonym.bitkitcore.NetworkType.BITCOIN,
                com.synonym.bitkitcore.AddressType.P2WPKH,
            )
        )
        stubWalletBackup(paykitPaymentState = state)
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
    }

    @Test
    fun `missing active followup blocks restore before installing a wallet guard`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val state = requireNotNull(json.decodeFromString<WalletBackupV1>(bytes.decodeToString()).paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        whenever(vssStoreIdProvider.getBackupWalletBinding(0)).thenReturn(wire.wallet.binding)
        stubWalletBackup(paykitPaymentState = state.copy(activeOnchainAttempt = wire.copy(followup = null)))

        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(onchainSendAttemptStore, never()).restoreActive(any())
        verify(paykitPaymentProofRepo, never()).restoreBackup(any())
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `Pubky unavailable does not prevent acknowledged USDT recovery backup`() = test {
        whenever(privatePaykitRepo.backupSnapshot()).thenReturn(
            Result.failure(PaykitException.SharedStateBusy("shared_state_busy", "lock unavailable")),
        )
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        sut.persistUsdtRecoveryBackup("same-signed-operation").getOrThrow()
        val payload = argumentCaptor<ByteArray>()
        verify(vssBackupClient).putObject(argThat { startsWith(UsdtRecoveryBackupStore.PREFIX) }, payload.capture())
        assertEquals(
            "same-signed-operation",
            json.decodeFromString<UsdtRecoveryBackupV1>(payload.firstValue.decodeToString()).usdtWallet,
        )
        verify(privatePaykitRepo, times(1)).backupSnapshot()
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `payment backup waits for earlier uploads and propagates failure`() = test {
        val release = CompletableDeferred<Unit>()
        val uploads = mutableListOf<String?>()
        var fail = false
        whenever(usdtRepo.backupSnapshot()).thenReturn("older")
        whenever {
            vssBackupClient.putObject(
                argThat { startsWith(UsdtRecoveryBackupStore.PREFIX) },
                any(),
            )
        }.doSuspendableAnswer {
            val payload = json.decodeFromString<UsdtRecoveryBackupV1>(it.getArgument<ByteArray>(1).decodeToString())
            if (payload.usdtWallet == "older") release.await()
            uploads += payload.usdtWallet
            if (fail) {
                Result.failure(BackupRepoTestError("offline"))
            } else {
                Result.success(VssItem(key = BackupCategory.WALLET.name, value = byteArrayOf(), version = 1))
            }
        }
        val background = async { sut.triggerBackup(BackupCategory.USDT_RECOVERY) }
        runCurrent()
        val payment = async { sut.persistUsdtRecoveryBackup("signed") }
        runCurrent()
        assertTrue(uploads.isEmpty())
        release.complete(Unit)
        background.await().getOrThrow()
        payment.await().getOrThrow()
        assertEquals(listOf<String?>("older", "signed"), uploads.toList())
        fail = true
        assertTrue(sut.persistUsdtRecoveryBackup("signed").isFailure)
    }

    @Test
    fun `payment backup retries setup before uploading`() = test {
        whenever { vssBackupClient.setup(any()) }
            .thenReturn(Result.failure(BackupRepoTestError("offline")))
            .thenReturn(Result.success(Unit))

        assertTrue(sut.persistUsdtRecoveryBackup("signed").isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())

        sut.persistUsdtRecoveryBackup("signed").getOrThrow()
        verify(vssBackupClient, times(2)).setup(any())
        verify(vssBackupClient).putObject(argThat { startsWith(UsdtRecoveryBackupStore.PREFIX) }, any())
    }

    @Test
    fun `restart after legacy migration restores only pending USDT state`() = test {
        whenever(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name)).thenReturn(true)
        whenever(keychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)).thenReturn(false)
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        verify(keychain).delete(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name)
        verify(privatePaykitRepo, never()).restoreBackup(anyOrNull())
        verify(vssBackupClient, never()).getObject(BackupCategory.METADATA.name)
        verify(vssBackupClient, never()).getObject(BackupCategory.SETTINGS.name)
        verify(keychain, never()).upsertString(eq(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name), any())
    }

    @Test
    fun `failed journal import retains legacy recovery and blocks replacement across restart`() = test {
        val key = Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name
        var retained: String? = null
        whenever(keychain.exists(key)).thenAnswer { retained != null }
        whenever(keychain.loadString(key)).thenAnswer { retained }
        whenever(keychain.upsertString(eq(key), any())).thenAnswer {
            retained = it.getArgument(1)
            Unit
        }
        whenever(keychain.delete(key)).thenAnswer {
            retained = null
            Unit
        }
        stubWalletBackup(usdtWallet = "legacy-signed-operation")
        whenever(vssBackupClient.listKeys()).thenReturn(Result.failure(BackupRepoTestError("offline")))

        assertTrue(sut.restoreUsdtRecoveryAfterLegacyMigration().isFailure)
        assertEquals(
            "legacy-signed-operation",
            json.decodeFromString<WalletBackupV1>(requireNotNull(retained)).usdtWallet,
        )
        assertTrue(sut.persistWalletBackup().isFailure)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
        verify(usdtRepo, never()).restoreBackup(any())

        sut = createSut()
        assertTrue(sut.hasPendingWalletRestore())
        assertTrue(sut.persistWalletBackup().isFailure)
        whenever(vssBackupClient.getObject(BackupCategory.WALLET.name))
            .thenReturn(Result.failure(BackupRepoTestError("remote wallet changed")))
        whenever(vssBackupClient.listKeys()).thenReturn(Result.success(emptyList()))
        whenever(usdtRepo.restoreBackup("legacy-signed-operation")).thenReturn(Result.success(Unit))

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertNull(retained)
        verify(usdtRepo).restoreBackup("legacy-signed-operation")
        verify(vssBackupClient, times(1)).getObject(BackupCategory.WALLET.name)
        verify(privatePaykitRepo, never()).restoreBackup(anyOrNull())
        verify(vssBackupClient, never()).getObject(BackupCategory.METADATA.name)
        verify(keychain, never()).upsertString(eq(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name), any())
    }

    @Test
    fun `core restore failure retains wallet backup`() = test {
        stubWalletBackup(usdtWallet = "signed")
        whenever { usdtRepo.restoreBackup("signed") }.thenReturn(Result.failure(BackupRepoTestError("storage")))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        val retained = argumentCaptor<String>()
        verify(keychain, times(2)).upsertString(eq(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name), retained.capture())
        assertEquals("signed", json.decodeFromString<WalletBackupV1>(retained.lastValue).usdtWallet)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
    }

    @Test
    fun `start observing is skipped while wiping`() = test {
        sut.setWiping(true)

        sut.startObservingBackups()
        runCurrent()

        verify(vssBackupClient, never()).setupWithRetry(any(), any(), any())
    }

    @Test
    fun `full restore should fail when private Paykit reservations fail to restore`() = test {
        stubWalletBackup()
        whenever { privatePaykitAddressReservationRepo.restoreBackup(anyOrNull(), any()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(privatePaykitRepo, never()).restoreBackup(any())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `failed Paykit restore retains its payload and blocks replacement backups until retry succeeds`() = test {
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubky-test-identity"))
        val key = Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name
        var retained: String? = null
        whenever(keychain.exists(key)).thenAnswer { retained != null }
        whenever(keychain.loadString(key)).thenAnswer { retained }
        whenever(keychain.delete(key)).thenAnswer {
            retained = null
            Unit
        }
        whenever(keychain.upsertString(eq(key), any())).thenAnswer {
            retained = it.getArgument(1)
            Unit
        }
        whenever(vssBackupClient.getObject(BackupCategory.WALLET.name))
            .thenReturn(Result.failure(BackupRepoTestError("download failed")))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertEquals("", retained)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)

        stubWalletBackup(
            paykitSdkBackupState = "saved-sdk-state",
            paykitPaymentState = PaykitPaymentStateBackup(emptyMap(), emptyList()),
        )
        whenever(privatePaykitRepo.restoreBackup("saved-sdk-state"))
            .thenReturn(Result.failure(BackupRepoTestError("unreadable state")))

        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertEquals(
            "saved-sdk-state",
            json.decodeFromString<WalletBackupV1>(requireNotNull(retained)).paykitSdkBackupState,
        )
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
        verify(settingsStore, never()).update(any())
        verify(paykitPaymentRequestRepo, never()).activate(any())

        whenever(vssBackupClient.getObject(BackupCategory.WALLET.name))
            .thenReturn(Result.failure(BackupRepoTestError("download unavailable")))
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertEquals(
            "saved-sdk-state",
            json.decodeFromString<WalletBackupV1>(requireNotNull(retained)).paykitSdkBackupState,
        )

        whenever(privatePaykitRepo.restoreBackup("saved-sdk-state")).thenReturn(Result.success(Unit))
        assertTrue(sut.performFullRestoreFromLatestBackup().isSuccess)
        assertNull(retained)
        verify(vssBackupClient, times(2)).getObject(BackupCategory.WALLET.name)
        verify(paykitPaymentRequestRepo).activate("pubky-test-identity")
    }

    @Test
    fun `cancelled wallet restore clears transient state and retains its backup guard`() = test {
        val key = Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name
        var retained: String? = null
        whenever(keychain.exists(key)).thenAnswer { retained != null }
        whenever(keychain.loadString(key)).thenAnswer { retained }
        whenever(keychain.upsertString(eq(key), any())).thenAnswer {
            retained = it.getArgument(1)
            Unit
        }
        whenever(vssBackupClient.getObject(BackupCategory.WALLET.name))
            .thenReturn(Result.failure(CancellationException("cancelled")))

        assertFailsWith<CancellationException> {
            sut.performFullRestoreFromLatestBackup()
        }

        assertFalse(sut.isRestoring.value)
        assertEquals("", retained)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
    }

    @Test
    fun `restore initializes storage before metadata and retains recovery guards on setup failure`() = test {
        stubPublicAddressRestore("bcrt1qpublic")
        whenever { vssBackupClient.setup() }.thenReturn(
            Result.failure(BackupRepoTestError("storage unavailable")),
            Result.success(Unit),
        )

        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)

        assertTrue(keychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name))
        assertTrue(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
        assertTrue(sut.hasPendingWalletRestore())
        verify(vssBackupClient, never()).getObject(any())

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertFalse(sut.hasPendingWalletRestore())
        assertFalse(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
    }

    @Test
    fun `metadata quarantine failure blocks restore and replacement backups until retry retains exposure`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(
            publicAddress,
            quarantineFailure = BackupRepoTestError("reservation store unavailable"),
        )
        var cacheRestored = false

        val result = sut.performFullRestoreFromLatestBackup(onCacheRestored = { cacheRestored = true })

        assertTrue(result.isFailure)
        assertFalse(sut.isRestoring.value)
        assertFalse(cacheRestored)
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.hasPendingWalletRestore())
        assertEquals("", keychain.loadString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name))
        assertTrue(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
        assertEquals(publicAddress, pendingPublicAddressRestoreRecord("address")["address"]?.jsonPrimitive?.content)
        assertEquals("", cacheData.value.onchainAddress)
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        BackupCategory.entries.filterNot { it == BackupCategory.METADATA }.forEach {
            verify(vssBackupClient, never()).getObject(it.name)
        }
        verify(privatePaykitRepo, never()).restoreBackup(anyOrNull())
        verify(settingsStore, never()).restoreFromBackup(any())
        verify(settingsStore, never()).update(any())
        verify(keychain, never()).delete(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)

        sut = createSut(createReservationRepo(reservationStore))
        assertTrue(sut.hasPendingWalletRestore())
        assertTrue(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        assertTrue(sut.persistWalletBackup().isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())

        sut.performFullRestoreFromLatestBackup(onCacheRestored = { cacheRestored = true }).getOrThrow()

        assertTrue(cacheRestored)
        assertTrue(settingsData.value.backupVerified)
        assertTrue(settingsData.value.sharesPrivatePaykitEndpoints)
        assertFalse(settingsData.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsData.value.legacyPublicAddressQuarantinePending)
        assertFalse(sut.hasPendingWalletRestore())
        assertFalse(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
        assertNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        assertEquals("", cacheData.value.onchainAddress)
        assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
        verify(privatePaykitRepo).restoreBackup(null)

        val restartedReservations = createReservationRepo(reservationStore)
        assertTrue(restartedReservations.isUnavailableForReusableReceive(publicAddress))
        sut = createSut(restartedReservations)
        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()

        val uploaded = argumentCaptor<ByteArray>()
        verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), uploaded.capture())
        val walletBackup = json.decodeFromString<WalletBackupV1>(uploaded.firstValue.decodeToString())
        assertEquals(listOf(publicAddress), walletBackup.publicPaykitLegacyAddresses)
    }

    @Test
    fun `pending public address restore retains exposure when retry metadata is unavailable`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(
            publicAddress,
            quarantineFailure = BackupRepoTestError("reservation store unavailable"),
        )
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertTrue(sut.hasPendingWalletRestore())
        assertFalse(settingsData.value.backupVerified)

        sut = createSut(createReservationRepo(reservationStore))
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name))
            .thenReturn(Result.failure(BackupRepoTestError("metadata download unavailable")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertFalse(sut.isRestoring.value)
        if (result.isSuccess) {
            assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
            assertTrue(settingsData.value.backupVerified)
            assertFalse(sut.hasPendingWalletRestore())
            val restartedReservations = createReservationRepo(reservationStore)
            assertTrue(restartedReservations.isUnavailableForReusableReceive(publicAddress))
            sut = createSut(restartedReservations)
            sut.triggerBackup(BackupCategory.WALLET).getOrThrow()

            val uploaded = argumentCaptor<ByteArray>()
            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), uploaded.capture())
            val walletBackup = json.decodeFromString<WalletBackupV1>(uploaded.firstValue.decodeToString())
            assertEquals(listOf(publicAddress), walletBackup.publicPaykitLegacyAddresses)
        } else {
            assertFalse(settingsData.value.backupVerified)
            assertTrue(sut.hasPendingWalletRestore())
            assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
        }
    }

    @Test
    fun `pending public address restore blocks metadata uploads after the transient gate clears`() = test {
        val reservationStore = stubPublicAddressRestore(
            "bcrt1qpublic",
            quarantineFailure = BackupRepoTestError("reservation store unavailable"),
        )
        sut.setRestorePending(true)
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        sut.setRestorePending(false)
        assertFalse(sut.isRestoring.value)
        assertTrue(sut.hasPendingWalletRestore())
        assertFalse(settingsData.value.backupVerified)
        assertEquals("", cacheData.value.onchainAddress)
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())

        val result = sut.triggerBackup(BackupCategory.METADATA)

        assertTrue(result.isFailure)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
        assertTrue(sut.hasPendingWalletRestore())
        assertFalse(settingsData.value.backupVerified)
    }

    @Test
    fun `first unavailable metadata download remains optional without a pending exposure`() = test {
        val reservationStore = stubPublicAddressRestore("bcrt1qpublic")
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name))
            .thenReturn(Result.failure(BackupRepoTestError("metadata download unavailable")))

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        sut.triggerBackup(BackupCategory.METADATA).getOrThrow()
        verify(vssBackupClient).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `cancelled metadata download permits absent metadata on retry`() = test {
        assertCancelledMetadataDownloadCanRetry(null)
    }

    @Test
    fun `cancelled metadata download permits valid blank metadata on retry`() = test {
        assertCancelledMetadataDownloadCanRetry(metadataEnvelope())
    }

    @Test
    fun `captured blank metadata source requires matching bytes before recovery completes`() = test {
        val reservationStore = stubPublicAddressRestore("")
        val metadata = metadataEnvelope()
        val reservations = createReservationRepo(reservationStore)
        assertFalse(reservations.preparePublicAddressRestore())
        reservations.capturePublicAddressRestoreSource(metadata.toByteArray())
        val source = pendingPublicAddressRestoreRecord("source")
        sut = createSut(createReservationRepo(reservationStore))

        for (download in listOf(null, "$metadata ")) {
            whenever(vssBackupClient.getObject(BackupCategory.METADATA.name)).thenReturn(
                Result.success(
                    download?.let {
                        VssItem(key = BackupCategory.METADATA.name, value = it.toByteArray(), version = 1)
                    },
                ),
            )
            assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
            assertFalse(settingsData.value.backupVerified)
            assertTrue(sut.hasPendingWalletRestore())
            assertEquals(source, pendingPublicAddressRestoreRecord("source"))
            assertTrue(sut.triggerBackup(BackupCategory.METADATA).isFailure)
        }
        verify(vssBackupClient, never()).getObject(BackupCategory.WALLET.name)
        verify(vssBackupClient, never()).putObject(any(), any())

        stubMetadataRestore(envelope = metadata)
        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        sut.triggerBackup(BackupCategory.METADATA).getOrThrow()
    }

    @Test
    fun `failed metadata source capture leaves reading guard without interpreting payload`() = test {
        val failure = BackupRepoTestError("source record unavailable")
        val reservationStore = stubPublicAddressRestore("bcrt1qpublic", sourceRecordFailure = failure)
        stubRestoreEnvelope(BackupCategory.METADATA, "{")

        val result = sut.performFullRestoreFromLatestBackup()

        assertEquals(failure, result.exceptionOrNull()?.cause)
        pendingPublicAddressRestoreRecord("reading")
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.hasPendingWalletRestore())
        assertEquals("", cacheData.value.onchainAddress)
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        verify(reservationStore, never()).update(any())
        verify(preActivityMetadataRepo, never()).migrateBackupPreActivityMetadataJson(any())
        verify(vssBackupClient, never()).getObject(BackupCategory.WALLET.name)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        assertTrue(sut.triggerBackup(BackupCategory.METADATA).isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())

        sut = createSut(createReservationRepo(reservationStore))
        stubMetadataRestore(envelope = metadataEnvelope())
        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
    }

    @Test
    fun `failed exposure recording keeps retry blocked until metadata recovers the address`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(
            publicAddress,
            addressRecordFailure = BackupRepoTestError("keychain unavailable"),
        )
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        val source = pendingPublicAddressRestoreRecord("source")
        assertTrue(source.getValue("fingerprint").jsonPrimitive.content.isNotBlank())
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        verify(vssBackupClient, never()).getObject(BackupCategory.WALLET.name)

        sut = createSut(createReservationRepo(reservationStore))
        val missingMetadata = listOf(
            Result.failure<VssItem?>(BackupRepoTestError("metadata download unavailable")),
            Result.success(null),
            Result.success(
                VssItem(
                    key = BackupCategory.METADATA.name,
                    value = metadataEnvelope().toByteArray(),
                    version = 1,
                ),
            ),
            Result.success(
                VssItem(
                    key = BackupCategory.METADATA.name,
                    value = metadataEnvelope(cache = AppCacheData(onchainAddress = publicAddress)).toByteArray(),
                    version = 1,
                ),
            ),
        )
        for (download in missingMetadata) {
            whenever(vssBackupClient.getObject(BackupCategory.METADATA.name)).thenReturn(download)
            assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
            assertFalse(settingsData.value.backupVerified)
            assertTrue(sut.hasPendingWalletRestore())
            assertEquals(source, pendingPublicAddressRestoreRecord("source"))
            assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
            assertTrue(sut.triggerBackup(BackupCategory.METADATA).isFailure)
        }
        verify(vssBackupClient, never()).putObject(any(), any())

        stubMetadataRestore(
            envelope = metadataEnvelope(
                metadata = listOf(preActivityMetadata()),
                cache = AppCacheData(onchainAddress = publicAddress),
            ),
        )
        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
        assertNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
    }

    @Test
    fun `cancelled exposure recording retains its prearmed recovery guard after restart`() = test {
        val reservationStore = stubPublicAddressRestore(
            "bcrt1qpublic",
            addressRecordFailure = CancellationException("cancelled"),
        )

        assertFailsWith<CancellationException> { sut.performFullRestoreFromLatestBackup() }

        assertFalse(sut.isRestoring.value)
        assertFalse(settingsData.value.backupVerified)
        pendingPublicAddressRestoreRecord("source")
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        sut = createSut(createReservationRepo(reservationStore))
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name))
            .thenReturn(Result.failure(BackupRepoTestError("metadata download unavailable")))

        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertTrue(sut.hasPendingWalletRestore())
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        assertTrue(sut.triggerBackup(BackupCategory.METADATA).isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `cancelled metadata quarantine preserves cancellation and the replacement backup guard`() = test {
        val reservationStore = stubPublicAddressRestore(
            "bcrt1qpublic",
            quarantineFailure = CancellationException("cancelled"),
        )
        var cacheRestored = false

        assertFailsWith<CancellationException> {
            sut.performFullRestoreFromLatestBackup(onCacheRestored = { cacheRestored = true })
        }

        assertFalse(sut.isRestoring.value)
        assertFalse(cacheRestored)
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.hasPendingWalletRestore())
        assertEquals("", keychain.loadString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name))
        assertTrue(keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name))
        assertEquals("bcrt1qpublic", pendingPublicAddressRestoreRecord("address")["address"]?.jsonPrimitive?.content)
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        verify(vssBackupClient, never()).getObject(BackupCategory.SETTINGS.name)
        verify(vssBackupClient, never()).getObject(BackupCategory.WALLET.name)
        verify(settingsStore, never()).update(any())
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `metadata cache failure remains optional after public address quarantine succeeds`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(publicAddress)
        var failCacheWrite = true
        doAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            val updated = transform(cacheData.value)
            if (failCacheWrite && updated.backupStatuses == cacheData.value.backupStatuses) {
                failCacheWrite = false
                throw BackupRepoTestError("cache store unavailable")
            }
            cacheData.update { updated }
        }.whenever(cacheStore).update(any())

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertFalse(settingsData.value.legacyPublicAddressQuarantinePending)
        assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
        verify(privatePaykitRepo).restoreBackup(null)
        assertTrue(createReservationRepo(reservationStore).isUnavailableForReusableReceive(publicAddress))
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `restored exposure stays pending when backup requirement persistence fails`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(publicAddress)
        var failRequirementWrite = true
        doAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            val updated = transform(cacheData.value)
            if (failRequirementWrite && updated.backupStatuses[BackupCategory.WALLET]?.isRequired == true) {
                failRequirementWrite = false
                throw BackupRepoTestError("backup status store unavailable")
            }
            cacheData.update { updated }
        }.whenever(cacheStore).update(any())

        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.hasPendingWalletRestore())
        assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
        assertEquals(publicAddress, pendingPublicAddressRestoreRecord("address")["address"]?.jsonPrimitive?.content)

        sut = createSut(createReservationRepo(reservationStore))
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name))
            .thenReturn(Result.failure(BackupRepoTestError("metadata download unavailable")))
        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.WALLET).isRequired)
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.METADATA).isRequired)
        assertTrue(sut.triggerBackup(BackupCategory.METADATA).isFailure)
        verify(vssBackupClient, never()).putObject(any(), any())
    }

    @Test
    fun `metadata replacement waits for normal wallet upload after local exposure restore`() = test {
        val publicAddress = "bcrt1qpublic"
        val reservationStore = stubPublicAddressRestore(publicAddress)
        val legacyMetadata = envelopeWithRawField(
            base = metadataEnvelope(cache = AppCacheData(onchainAddress = publicAddress)),
            field = "tagMetadata",
            rawJson = LEGACY_METADATA_JSON,
        )
        val remote = mutableMapOf(
            BackupCategory.METADATA.name to legacyMetadata.toByteArray(),
            BackupCategory.WALLET.name to """{"version":1,"createdAt":123,"transfers":[]}""".toByteArray(),
        )
        whenever(vssBackupClient.getObject(any())).doSuspendableAnswer {
            val key = it.getArgument<String>(0)
            Result.success(remote[key]?.let { value -> VssItem(key = key, value = value, version = 1) })
        }
        var walletUploadAvailable = false
        val uploadedCategories = mutableListOf<String>()
        whenever(vssBackupClient.putObject(any(), any())).doSuspendableAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<ByteArray>(1)
            if (key == BackupCategory.WALLET.name && !walletUploadAvailable) {
                Result.failure(BackupRepoTestError("wallet upload unavailable"))
            } else {
                remote[key] = value
                uploadedCategories += key
                Result.success(VssItem(key = key, value = value, version = 1))
            }
        }
        val connectionStatus = BackupItemStatus(synced = 10, required = 20)
        cacheData.update {
            it.copy(backupStatuses = mapOf(BackupCategory.LIGHTNING_CONNECTIONS to connectionStatus))
        }

        sut.performFullRestoreFromLatestBackup().getOrThrow()

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertEquals(setOf(publicAddress), reservationStore.data.first().quarantinedPublicAddresses)
        assertEquals(legacyMetadata, remote.getValue(BackupCategory.METADATA.name).decodeToString())
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.WALLET).isRequired)
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.METADATA).isRequired)
        assertEquals(connectionStatus, cacheData.value.backupStatuses[BackupCategory.LIGHTNING_CONNECTIONS])
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

        sut.triggerBackup(BackupCategory.METADATA)
        assertTrue(sut.triggerBackup(BackupCategory.WALLET).isFailure)
        sut.triggerBackup(BackupCategory.METADATA)
        assertEquals(legacyMetadata, remote.getValue(BackupCategory.METADATA.name).decodeToString())
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.WALLET).isRequired)
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.METADATA).isRequired)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())

        walletUploadAvailable = true
        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()
        val wallet = json.decodeFromString<WalletBackupV1>(remote.getValue(BackupCategory.WALLET.name).decodeToString())
        assertEquals(listOf(publicAddress), wallet.publicPaykitLegacyAddresses)
        sut.triggerBackup(BackupCategory.METADATA).getOrThrow()
        val metadata = json.decodeFromString<MetadataBackupV1>(
            remote.getValue(BackupCategory.METADATA.name).decodeToString(),
        )
        assertEquals("", metadata.cache.onchainAddress)
        assertEquals(BackupCategory.WALLET.name, uploadedCategories.first())
        assertTrue(uploadedCategories.indexOf(BackupCategory.METADATA.name) > 0)
        assertFalse(cacheData.value.backupStatuses.getValue(BackupCategory.WALLET).isRequired)
        assertFalse(cacheData.value.backupStatuses.getValue(BackupCategory.METADATA).isRequired)
    }

    @Test
    fun `metadata restore waits for admitted metadata capture and upload to drain`() = test {
        stubMetadataBackupReads()
        val captureStarted = CompletableDeferred<Unit>()
        val finishCapture = CompletableDeferred<Unit>()
        val uploadStarted = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val metadataRead = CompletableDeferred<Unit>()
        whenever(pubkyRepo.snapshotSessionBackupState()).doSuspendableAnswer {
            captureStarted.complete(Unit)
            finishCapture.await()
            Result.success(null)
        }
        whenever(vssBackupClient.putObject(eq(BackupCategory.METADATA.name), any())).doSuspendableAnswer {
            uploadStarted.complete(Unit)
            finishUpload.await()
            Result.success(VssItem(key = BackupCategory.METADATA.name, value = it.getArgument(1), version = 1))
        }
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name)).doSuspendableAnswer {
            metadataRead.complete(Unit)
            Result.success(null)
        }
        val backup = async { sut.triggerBackup(BackupCategory.METADATA) }
        captureStarted.await()
        val restore = async { sut.performFullRestoreFromLatestBackup() }
        try {
            runCurrent()
            assertFalse(metadataRead.isCompleted)
            assertFalse(restore.isCompleted)
            finishCapture.complete(Unit)
            runCurrent()
            assertTrue(uploadStarted.isCompleted)
            assertFalse(metadataRead.isCompleted)
            assertFalse(restore.isCompleted)

            finishUpload.complete(Unit)
            backup.await().getOrThrow()
            restore.await().getOrThrow()
            assertTrue(metadataRead.isCompleted)
        } finally {
            finishCapture.complete(Unit)
            finishUpload.complete(Unit)
            backup.cancelAndJoin()
            restore.cancelAndJoin()
        }
    }

    @Test
    fun `automatic wallet backup marks failure when Paykit snapshot fails`() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(3_000))
        whenever { privatePaykitRepo.backupSnapshot() }
            .thenReturn(Result.failure(BackupRepoTestError("paykit session missing capabilities")))
        val backupStatuses = MutableStateFlow(
            mapOf(
                BackupCategory.WALLET to BackupItemStatus(
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

            verify(privatePaykitRepo).backupSnapshot()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
            val status = backupStatuses.value.getValue(BackupCategory.WALLET)
            assertTrue(status.isRequired)
            assertFalse(status.running)

            advanceTimeBy(5_000)
            runCurrent()

            // still one attempt: the failed timestamp suppresses a retry until data changes again
            verify(privatePaykitRepo, times(1)).backupSnapshot()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `wallet backup retries after a busy Paykit shared state`() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(3_000))
        whenever { privatePaykitRepo.backupSnapshot() }
            .thenReturn(Result.failure(PaykitException.SharedStateBusy("shared_state_busy", "locked")))
            .thenReturn(Result.success(PrivatePaykitBackupSnapshot(null, 0)))
        val backupStatuses = MutableStateFlow(
            mapOf(
                BackupCategory.WALLET to BackupItemStatus(
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
            verify(privatePaykitRepo, times(1)).backupSnapshot()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

            advanceTimeBy(20_000)
            runCurrent()

            verify(privatePaykitRepo, times(2)).backupSnapshot()
            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), any())
            assertFalse(backupStatuses.value.getValue(BackupCategory.WALLET).isRequired)
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `busy wallet backup retries after observation pauses and resumes`() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(3_000))
        whenever { privatePaykitRepo.backupSnapshot() }
            .thenReturn(Result.failure(PaykitException.SharedStateBusy("shared_state_busy", "locked")))
            .thenReturn(Result.success(PrivatePaykitBackupSnapshot(null, 0)))
        val backupStatuses = MutableStateFlow(
            mapOf(BackupCategory.WALLET to BackupItemStatus(synced = 1_000, required = 2_000))
        )
        val allowWalletClear = CompletableDeferred<Unit>().apply { complete(Unit) }
        stubBackupStatuses(backupStatuses, allowWalletClear) {}
        stubBackupObservers()

        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            sut.stopObservingBackups()
            advanceTimeBy(20_000)
            runCurrent()
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.WALLET.name), any())

            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `busy wallet backup still warns once it stays pending past the failure window`() = test {
        val startedAt = currentTime
        whenever(clock.now()).thenAnswer { Instant.fromEpochMilliseconds(3_000 + currentTime - startedAt) }
        whenever { privatePaykitRepo.backupSnapshot() }
            .thenReturn(Result.failure(PaykitException.SharedStateBusy("shared_state_busy", "locked")))
        whenever(context.getString(R.string.settings__backup__failed_title)).thenReturn("Backup failed")
        whenever(context.getString(R.string.settings__backup__failed_message)).thenReturn("Retrying")
        val backupStatuses = MutableStateFlow(
            mapOf(BackupCategory.WALLET to BackupItemStatus(synced = 1_000, required = 2_000))
        )
        val allowWalletClear = CompletableDeferred<Unit>().apply { complete(Unit) }
        stubBackupStatuses(backupStatuses, allowWalletClear) {}
        stubBackupObservers()

        try {
            sut.startObservingBackups()
            runCurrent()
            advanceTimeBy(31.minutes.inWholeMilliseconds)
            runCurrent()

            verify(context, atLeastOnce()).getString(R.string.settings__backup__failed_title)
        } finally {
            sut.stopObservingBackups()
        }
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
    fun `successful upload leaves a newer required snapshot queued`() = test {
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
                    Result.success(VssItem(key = BackupCategory.SETTINGS.name, value = byteArrayOf(), version = 1))
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
    fun `full restore should fail when Paykit initialization fails without a saved SDK backup`() = test {
        stubWalletBackup()
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(settingsStore, never()).update(any())
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
    fun `wallet backup round trips public address exclusions alongside reservation floors`() = test {
        val legacy = setOf("bcrt1qpublic")
        val floors = mapOf("nativeSegwit" to 8)
        whenever(privatePaykitAddressReservationRepo.quarantinedPublicAddressesSnapshot()).thenReturn(legacy)
        whenever(privatePaykitAddressReservationRepo.backupSnapshot()).thenReturn(Result.success(floors))
        sut.triggerBackup(BackupCategory.WALLET).getOrThrow()
        val bytes = argumentCaptor<ByteArray>()
        verify(vssBackupClient).putObject(eq(BackupCategory.WALLET.name), bytes.capture())
        val payload = json.decodeFromString<WalletBackupV1>(bytes.firstValue.decodeToString())
        assertEquals(legacy.toList(), payload.publicPaykitLegacyAddresses)
        assertEquals(floors, payload.privatePaykitHighestReservedReceiveIndexByAddressType)
        whenever(vssBackupClient.getObject(BackupCategory.WALLET.name)).thenReturn(
            Result.success(VssItem(BackupCategory.WALLET.name, bytes.firstValue, 1)),
        )
        sut.performFullRestoreFromLatestBackup().getOrThrow()
        verify(privatePaykitAddressReservationRepo).restoreBackup(floors, legacy)
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

    @Test
    fun `legacy activity backup is migrated by core before decode`() = test {
        stubWalletBackup()
        stubActivityRestore()

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verifyBlocking(activityRepo) { migrateBackupActivitiesJson(LEGACY_ACTIVITIES_JSON) }
        verifyBlocking(activityRepo) { migrateBackupActivityTagsJson(LEGACY_TAGS_JSON) }

        val payloadCaptor = argumentCaptor<ActivityBackupV1>()
        verifyBlocking(activityRepo) { restoreFromBackup(payloadCaptor.capture()) }
        assertEquals(listOf(defaultTag()), payloadCaptor.firstValue.activityTags)
    }

    @Test
    fun `legacy metadata backup is migrated by core before decode`() = test {
        stubWalletBackup()
        stubMetadataRestore()

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verifyBlocking(preActivityMetadataRepo) { migrateBackupPreActivityMetadataJson(LEGACY_METADATA_JSON) }

        val metadataCaptor = argumentCaptor<List<PreActivityMetadata>>()
        verifyBlocking(preActivityMetadataRepo) { upsertPreActivityMetadata(metadataCaptor.capture()) }
        assertEquals(listOf(preActivityMetadata()), metadataCaptor.firstValue)
    }

    @Test
    fun `migrated backups are rewritten to vss after restore`() = test {
        stubWalletBackup()
        stubActivityRestore()
        stubMetadataRestore()

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.ACTIVITY.name), any()) }
        verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.METADATA.name), any()) }
    }

    @Test
    fun `current backups are not rewritten after restore`() = test {
        stubWalletBackup()
        // Envelopes already carry wallet ids, so Core hands the slices back untouched.
        stubActivityRestore(envelope = activityEnvelope(tags = listOf(defaultTag())))
        stubMetadataRestore(envelope = metadataEnvelope(metadata = listOf(preActivityMetadata())))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.ACTIVITY.name), any())
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `metadata backup carries hardware tags as pre-activity metadata`() = test {
        val hardwareTagMetadata = preActivityMetadata().copy(
            walletId = HARDWARE_WALLET_ID,
            paymentId = "hw-txid",
            tags = listOf("hw"),
        )
        whenever { preActivityMetadataRepo.getAllPreActivityMetadata() }
            .thenReturn(Result.success(listOf(preActivityMetadata())))
        whenever { activityRepo.getHardwareTagsAsPreActivityMetadata() }
            .thenReturn(Result.success(listOf(hardwareTagMetadata)))
        whenever { pubkyRepo.snapshotSessionBackupState() }.thenReturn(Result.success(null))
        whenever { pubkyRepo.snapshotContactProfileOverrides() }.thenReturn(Result.success(null))
        val dataCaptor = argumentCaptor<ByteArray>()

        sut.triggerBackup(BackupCategory.METADATA)

        verifyBlocking(vssBackupClient) {
            putObject(eq(BackupCategory.METADATA.name), dataCaptor.capture())
        }
        val payload = json.decodeFromString<MetadataBackupV1>(dataCaptor.firstValue.decodeToString())
        assertEquals(listOf(preActivityMetadata(), hardwareTagMetadata), payload.tagMetadata)
    }

    @Test
    fun `metadata backup carries the hardware wallet names`() = test {
        stubMetadataBackupReads()
        whenever { hwWalletStore.backupSnapshot() }.thenReturn(mapOf(HARDWARE_WALLET_ID to "Cold Storage"))
        val dataCaptor = argumentCaptor<ByteArray>()

        sut.triggerBackup(BackupCategory.METADATA)

        verifyBlocking(vssBackupClient) {
            putObject(eq(BackupCategory.METADATA.name), dataCaptor.capture())
        }
        val payload = json.decodeFromString<MetadataBackupV1>(dataCaptor.firstValue.decodeToString())
        assertEquals(mapOf(HARDWARE_WALLET_ID to "Cold Storage"), payload.hwWalletNames)
    }

    @Test
    fun `metadata backup omits the hardware wallet names when none are set`() = test {
        stubMetadataBackupReads()
        val dataCaptor = argumentCaptor<ByteArray>()

        sut.triggerBackup(BackupCategory.METADATA)

        verifyBlocking(vssBackupClient) {
            putObject(eq(BackupCategory.METADATA.name), dataCaptor.capture())
        }
        val payload = json.decodeFromString<MetadataBackupV1>(dataCaptor.firstValue.decodeToString())
        assertNull(payload.hwWalletNames)
    }

    @Test
    fun `metadata backup fails when the hardware wallet names cannot be read`() = test {
        stubMetadataBackupReads()
        whenever { hwWalletStore.backupSnapshot() }
            .doSuspendableAnswer { throw BackupRepoTestError("store unavailable") }

        val result = sut.triggerBackup(BackupCategory.METADATA)

        assertTrue(result.isFailure)
        // This envelope is the only copy of the names, so uploading without them would drop them.
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `metadata restore applies the backed up hardware wallet names`() = test {
        val names = mapOf(HARDWARE_WALLET_ID to "Cold Storage")
        stubMetadataRestore(
            envelope = metadataEnvelope(metadata = listOf(preActivityMetadata()), hwWalletNames = names),
        )

        sut.performFullRestoreFromLatestBackup()

        verifyBlocking(hwWalletStore) { restoreNames(names) }
    }

    @Test
    fun `metadata restore keeps the stored names when the envelope carries none`() = test {
        stubMetadataRestore(envelope = metadataEnvelope(metadata = listOf(preActivityMetadata())))

        sut.performFullRestoreFromLatestBackup()

        // An envelope written before this field must not clear what this wallet already holds.
        verifyBlocking(hwWalletStore) { restoreNames(emptyMap()) }
    }

    @Test
    fun `metadata restore records the category when the hardware wallet names cannot be stored`() = test {
        stubWalletBackup()
        stubMetadataRestore(
            envelope = metadataEnvelope(
                metadata = listOf(preActivityMetadata()),
                hwWalletNames = mapOf(HARDWARE_WALLET_ID to "Cold Storage"),
            ),
        )
        whenever { hwWalletStore.restoreNames(any()) }
            .doSuspendableAnswer { throw BackupRepoTestError("store unavailable") }

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        // The names are the last and the least of this envelope: the caches and tags above them were
        // already applied, so losing the whole category's synced marker over a name would be wrong.
        verifyBlocking(cacheStore) { updateBackupStatus(eq(BackupCategory.METADATA), any()) }
    }

    @Test
    fun `renaming a hardware wallet triggers a metadata backup`() = test {
        stubMetadataBackupReads()
        stubBackupObservers()
        stubBackupStatuses(
            MutableStateFlow(emptyMap()),
            CompletableDeferred<Unit>().apply { complete(Unit) },
        ) {}

        try {
            sut.startObservingBackups()
            runCurrent()

            // Reconnecting rewrites the entry without touching its name.
            hwWalletData.update { HwWalletData(knownDevices = listOf(knownDevice())) }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())

            hwWalletData.update { HwWalletData(knownDevices = listOf(knownDevice(customLabel = "Cold Storage"))) }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.METADATA.name), any()) }
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `activity traffic alone does not trigger a metadata backup`() = test {
        val activitiesChanged = MutableStateFlow(0L)
        val activityTagsChanged = MutableStateFlow(0L)
        stubMetadataBackupReads()
        stubBackupObservers()
        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChanged)
        whenever(activityRepo.activityTagsChanged).thenReturn(activityTagsChanged)
        stubBackupStatuses(
            MutableStateFlow(emptyMap()),
            CompletableDeferred<Unit>().apply { complete(Unit) },
        ) {}

        try {
            sut.startObservingBackups()
            runCurrent()

            // A payment or sync bumps activities without touching tags.
            activitiesChanged.update { 1L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())

            // Tagging does bump the tag signal, which the metadata backup must follow.
            activityTagsChanged.update { 2L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.METADATA.name), any()) }
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `ordinary activity upload is skipped while a restore is pending`() = test {
        val activitiesChanged = MutableStateFlow(0L)
        stubBackupObservers()
        stubActivityBackupReads()
        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChanged)
        stubBackupStatuses(
            MutableStateFlow(emptyMap()),
            CompletableDeferred<Unit>().apply { complete(Unit) },
        ) {}

        try {
            // The restore flow announces itself long before it reads the backup.
            sut.setRestorePending(true)
            sut.startObservingBackups()
            runCurrent()

            // Meanwhile the node started and synced the fresh wallet's activities.
            activitiesChanged.update { 1L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            // Uploading here replaces the stored envelope with the fresh wallet's state before the
            // restore reads it, which loses the tags and the closed channels it still holds.
            verify(vssBackupClient, never()).putObject(eq(BackupCategory.ACTIVITY.name), any())
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `ordinary activity upload resumes once the restore is no longer pending`() = test {
        val activitiesChanged = MutableStateFlow(0L)
        stubBackupObservers()
        stubActivityBackupReads()
        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChanged)
        stubBackupStatuses(
            MutableStateFlow(emptyMap()),
            CompletableDeferred<Unit>().apply { complete(Unit) },
        ) {}

        try {
            sut.setRestorePending(true)
            sut.startObservingBackups()
            runCurrent()

            activitiesChanged.update { 1L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verify(vssBackupClient, never()).putObject(eq(BackupCategory.ACTIVITY.name), any())

            sut.setRestorePending(false)
            whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(2_000))

            activitiesChanged.update { 2L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.ACTIVITY.name), any()) }
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `pending restore stops gating uploads once it expires`() = test {
        val activitiesChanged = MutableStateFlow(0L)
        stubBackupObservers()
        stubActivityBackupReads()
        whenever(activityRepo.activitiesChanged).thenReturn(activitiesChanged)
        stubBackupStatuses(
            MutableStateFlow(emptyMap()),
            CompletableDeferred<Unit>().apply { complete(Unit) },
        ) {}

        try {
            sut.setRestorePending(true)
            sut.startObservingBackups()
            runCurrent()

            // The restore never returns, so nothing ever clears the gate.
            whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(RESTORE_GATE_EXPIRED_AT))

            activitiesChanged.update { 1L }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.ACTIVITY.name), any()) }
        } finally {
            sut.stopObservingBackups()
        }
    }

    @Test
    fun `migration rewrite still uploads while a restore is pending`() = test {
        stubWalletBackup()
        stubActivityRestore()
        sut.setRestorePending(true)

        val result = sut.performFullRestoreFromLatestBackup()

        // The rewrite is the restore's own upload, so the gate must never hold it.
        assertTrue(result.isSuccess)
        verifyBlocking(vssBackupClient) { putObject(eq(BackupCategory.ACTIVITY.name), any()) }
    }

    @Test
    fun `metadata backup fails when pre-activity metadata cannot be read`() = test {
        stubMetadataBackupReads()
        whenever { preActivityMetadataRepo.getAllPreActivityMetadata() }
            .thenReturn(Result.failure(BackupRepoTestError("core unavailable")))

        val result = sut.triggerBackup(BackupCategory.METADATA)

        assertTrue(result.isFailure)
        // Uploading here would replace the stored tags with an empty set.
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `metadata backup fails when hardware tags cannot be read`() = test {
        stubMetadataBackupReads()
        whenever { activityRepo.getHardwareTagsAsPreActivityMetadata() }
            .thenReturn(Result.failure(BackupRepoTestError("core unavailable")))

        val result = sut.triggerBackup(BackupCategory.METADATA)

        assertTrue(result.isFailure)
        // This envelope is the only copy of hardware tags, so a partial upload would lose them.
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.METADATA.name), any())
    }

    @Test
    fun `activity backup excludes hardware tags`() = test {
        whenever { activityRepo.getActivities() }.thenReturn(Result.success(emptyList()))
        whenever { activityRepo.getClosedChannels() }.thenReturn(Result.success(emptyList()))
        // ActivityRepo already scopes this to the default wallet, so a hardware tag can never appear here.
        whenever { activityRepo.getAllActivitiesTags() }.thenReturn(Result.success(listOf(defaultTag())))
        val dataCaptor = argumentCaptor<ByteArray>()

        sut.triggerBackup(BackupCategory.ACTIVITY)

        verifyBlocking(vssBackupClient) {
            putObject(eq(BackupCategory.ACTIVITY.name), dataCaptor.capture())
        }
        val payload = json.decodeFromString<ActivityBackupV1>(dataCaptor.firstValue.decodeToString())
        assertTrue(payload.activityTags.none { it.walletId == HARDWARE_WALLET_ID })
    }

    @Test
    fun `activity backup is not rewritten when core restore fails`() = test {
        stubWalletBackup()
        stubActivityRestore()
        whenever { activityRepo.restoreFromBackup(any()) }
            .thenReturn(Result.failure(BackupRepoTestError("upsert failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isSuccess)
        // Rewriting here would replace the legacy backup with whatever Core happens to hold.
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.ACTIVITY.name), any())
    }

    @Test
    fun `failed core migration skips the category without failing the restore`() = test {
        stubWalletBackup()
        stubActivityRestore()
        whenever { activityRepo.migrateBackupActivityTagsJson(any()) }
            .thenReturn(Result.failure(BackupRepoTestError("core migration failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        // Only WALLET is fatal, so the restore still reports success overall.
        assertTrue(result.isSuccess)
        // The legacy slice stays unmigrated and no longer decodes, so nothing of this category is applied.
        verifyBlocking(activityRepo, never()) { restoreFromBackup(any()) }
        // Nothing was restored, so the stored backup must not be replaced.
        verify(vssBackupClient, never()).putObject(eq(BackupCategory.ACTIVITY.name), any())
    }

    private fun stubActivityBackupReads() {
        whenever { activityRepo.getActivities() }.thenReturn(Result.success(emptyList()))
        whenever { activityRepo.getClosedChannels() }.thenReturn(Result.success(emptyList()))
        whenever { activityRepo.getAllActivitiesTags() }.thenReturn(Result.success(emptyList()))
    }

    private fun pendingPublicAddressRestoreRecord(type: String): JsonObject {
        val stored = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        return json.parseToJsonElement(stored).jsonObject.also {
            assertEquals(type, it["type"]?.jsonPrimitive?.content)
        }
    }

    private suspend fun assertCancelledMetadataDownloadCanRetry(metadataOnRetry: String?) = coroutineScope {
        val reservationStore = stubPublicAddressRestore("")
        whenever(preActivityMetadataRepo.migrateBackupPreActivityMetadataJson(any())).thenAnswer {
            Result.success(it.getArgument<String>(0))
        }
        whenever(preActivityMetadataRepo.getAllPreActivityMetadata()).thenReturn(Result.success(emptyList()))
        val downloadStarted = CompletableDeferred<Unit>()
        var holdDownload = true
        val metadata = metadataOnRetry?.let {
            VssItem(key = BackupCategory.METADATA.name, value = it.toByteArray(), version = 1)
        }
        whenever(vssBackupClient.getObject(BackupCategory.METADATA.name)).doSuspendableAnswer {
            if (holdDownload) {
                downloadStarted.complete(Unit)
                awaitCancellation()
            }
            Result.success(metadata)
        }
        val restore = async { sut.performFullRestoreFromLatestBackup() }
        try {
            downloadStarted.await()
        } finally {
            restore.cancelAndJoin()
        }

        assertTrue(restore.isCancelled)
        assertFalse(sut.isRestoring.value)
        assertFalse(settingsData.value.backupVerified)
        assertTrue(sut.hasPendingWalletRestore())
        pendingPublicAddressRestoreRecord("reading")
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        verify(vssBackupClient, never()).getObject(BackupCategory.WALLET.name)

        holdDownload = false
        val restartedReservations = createReservationRepo(reservationStore)
        sut = createSut(restartedReservations)
        sut.setRestorePending(false)
        assertTrue(sut.performFullRestoreFromLatestBackup().isSuccess)

        assertTrue(settingsData.value.backupVerified)
        assertFalse(sut.hasPendingWalletRestore())
        assertNull(keychain.loadString(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        assertTrue(reservationStore.data.first().quarantinedPublicAddresses.isEmpty())
        whenever(lightningRepo.newAddressInfoForType(any()))
            .thenReturn(Result.success(AddressDerivationInfo("bcrt1qreceive", 0)))
        assertEquals("bcrt1qreceive", restartedReservations.nextReusableReceiveAddress().getOrThrow())
        sut.triggerBackup(BackupCategory.METADATA).getOrThrow()
        verify(vssBackupClient).putObject(eq(BackupCategory.METADATA.name), any())
    }

    private fun stubPublicAddressRestore(
        address: String,
        quarantineFailure: Throwable? = null,
        addressRecordFailure: Throwable? = null,
        sourceRecordFailure: Throwable? = null,
    ): PrivatePaykitReservationStore {
        stubMetadataRestore(
            envelope = metadataEnvelope(
                metadata = listOf(preActivityMetadata()),
                cache = AppCacheData(onchainAddress = address),
            ),
        )
        stubRestoreEnvelope(BackupCategory.WALLET, """{"version":1,"createdAt":123,"transfers":[]}""")
        stubRestoreEnvelope(
            BackupCategory.SETTINGS,
            json.encodeToString(
                SettingsBackupV1(
                    createdAt = 123,
                    settings = SettingsData(
                        sharesPrivatePaykitEndpoints = true,
                        sharesPublicPaykitEndpoints = true,
                        hasConfirmedPublicPaykitEndpoints = true,
                    ),
                ),
            ),
        )
        whenever { settingsStore.restoreFromBackup(any()) }.thenAnswer {
            val payload = it.getArgument<SettingsBackupV1>(0)
            settingsData.update { payload.settings.withPublicSharingDisabledForRestore() }
            Result.success(Unit)
        }
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.update(transform)
        }
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.update(transform)
        }
        whenever(cacheStore.backupStatuses).thenReturn(cacheData.map { it.backupStatuses })
        whenever { cacheStore.updateBackupStatus(any(), any()) }.thenAnswer {
            val category = it.getArgument<BackupCategory>(0)
            val transform = it.getArgument<(BackupItemStatus) -> BackupItemStatus>(1)
            cacheData.update { cache ->
                val status = cache.backupStatuses[category] ?: BackupItemStatus()
                cache.copy(backupStatuses = cache.backupStatuses + (category to transform(status)))
            }
        }
        val retained = mutableMapOf<String, String>()
        var nextRecordFailure = addressRecordFailure
        var nextSourceFailure = sourceRecordFailure
        whenever(keychain.exists(any())).thenAnswer { it.getArgument<String>(0) in retained }
        whenever(keychain.loadString(any())).thenAnswer { retained[it.getArgument<String>(0)] }
        whenever { keychain.upsertString(any(), any()) }.thenAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<String>(1)
            if (key == Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name) {
                when (json.parseToJsonElement(value).jsonObject["type"]?.jsonPrimitive?.content) {
                    "source" -> nextSourceFailure?.let { failure ->
                        nextSourceFailure = null
                        throw failure
                    }
                    "address" -> nextRecordFailure?.let { failure ->
                        nextRecordFailure = null
                        throw failure
                    }
                }
            }
            retained[key] = value
            Unit
        }
        whenever { keychain.delete(any()) }.thenAnswer {
            retained.remove(it.getArgument<String>(0))
            Unit
        }
        val reservationStore = mock<PrivatePaykitReservationStore>()
        val reservationData = MutableStateFlow(PrivatePaykitReservationData())
        var nextFailure = quarantineFailure
        whenever(reservationStore.data).thenReturn(reservationData)
        whenever { reservationStore.update(any()) }.thenAnswer {
            nextFailure?.let { failure ->
                nextFailure = null
                throw failure
            }
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            reservationData.update(transform)
        }
        sut = createSut(createReservationRepo(reservationStore))
        return reservationStore
    }

    private fun createReservationRepo(
        reservationStore: PrivatePaykitReservationStore,
    ) = PrivatePaykitAddressReservationRepo(
        ioDispatcher = testDispatcher,
        reservationStore = reservationStore,
        settingsStore = settingsStore,
        coreService = coreService,
        lightningRepo = lightningRepo,
        cacheStore = cacheStore,
        keychain = keychain,
    )

    private fun stubMetadataBackupReads() {
        whenever { preActivityMetadataRepo.getAllPreActivityMetadata() }
            .thenReturn(Result.success(listOf(preActivityMetadata())))
        whenever { activityRepo.getHardwareTagsAsPreActivityMetadata() }.thenReturn(Result.success(emptyList()))
        whenever { pubkyRepo.snapshotSessionBackupState() }.thenReturn(Result.success(null))
        whenever { pubkyRepo.snapshotContactProfileOverrides() }.thenReturn(Result.success(null))
    }

    private fun stubActivityRestore(
        envelope: String = legacyActivityEnvelope(),
        migratedTagsJson: String = json.encodeToString(listOf(defaultTag())),
        backedUpTags: List<ActivityTags> = listOf(defaultTag()),
    ) {
        stubRestoreEnvelope(BackupCategory.ACTIVITY, envelope)
        whenever { activityRepo.migrateBackupActivitiesJson(any()) }.thenReturn(Result.success("[]"))
        whenever { activityRepo.migrateBackupActivityTagsJson(any()) }.thenReturn(Result.success(migratedTagsJson))
        whenever { activityRepo.restoreFromBackup(any()) }.thenReturn(Result.success(Unit))
        // Read back by the rewrite through getBackupDataBytes(ACTIVITY).
        whenever { activityRepo.getActivities() }.thenReturn(Result.success(emptyList()))
        whenever { activityRepo.getClosedChannels() }.thenReturn(Result.success(emptyList()))
        whenever { activityRepo.getAllActivitiesTags() }.thenReturn(Result.success(backedUpTags))
    }

    private fun stubMetadataRestore(
        envelope: String = legacyMetadataEnvelope(),
        migratedMetadataJson: String = json.encodeToString(listOf(preActivityMetadata())),
        restorableMetadata: List<PreActivityMetadata> = listOf(preActivityMetadata()),
    ) {
        stubRestoreEnvelope(BackupCategory.METADATA, envelope)
        whenever { preActivityMetadataRepo.migrateBackupPreActivityMetadataJson(any()) }
            .thenReturn(Result.success(migratedMetadataJson))
        whenever { preActivityMetadataRepo.upsertPreActivityMetadata(any()) }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.restoreSessionBackupState(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.restoreContactProfileOverrides(anyOrNull()) }.thenReturn(Result.success(Unit))
        // Read back by the rewrite through getMetadataBackupDataBytes().
        whenever { preActivityMetadataRepo.getAllPreActivityMetadata() }
            .thenReturn(Result.success(restorableMetadata))
        whenever { activityRepo.getHardwareTagsAsPreActivityMetadata() }.thenReturn(Result.success(emptyList()))
        whenever { pubkyRepo.snapshotSessionBackupState() }.thenReturn(Result.success(null))
        whenever { pubkyRepo.snapshotContactProfileOverrides() }.thenReturn(Result.success(null))
    }

    private fun stubRestoreEnvelope(category: BackupCategory, envelope: String) {
        whenever { vssBackupClient.getObject(category.name) }.thenReturn(
            Result.success(
                VssItem(key = category.name, value = envelope.toByteArray(), version = 1)
            )
        )
    }

    /** An `ActivityBackupV1` whose Core-owned tag records predate `walletId`. */
    private fun legacyActivityEnvelope(): String = envelopeWithRawField(
        base = activityEnvelope(),
        field = "activityTags",
        rawJson = LEGACY_TAGS_JSON,
    )

    /** A `MetadataBackupV1` whose Core-owned metadata records predate `walletId`. */
    private fun legacyMetadataEnvelope(): String = envelopeWithRawField(
        base = metadataEnvelope(),
        field = "tagMetadata",
        rawJson = LEGACY_METADATA_JSON,
    )

    private fun activityEnvelope(tags: List<ActivityTags> = emptyList()) = json.encodeToString(
        ActivityBackupV1(
            createdAt = 123,
            activities = emptyList(),
            activityTags = tags,
            closedChannels = emptyList(),
        )
    )

    private fun metadataEnvelope(
        metadata: List<PreActivityMetadata> = emptyList(),
        cache: AppCacheData = AppCacheData(),
        hwWalletNames: Map<String, String>? = null,
    ) = json.encodeToString(
        MetadataBackupV1(
            createdAt = 123,
            tagMetadata = metadata,
            cache = cache,
            hwWalletNames = hwWalletNames,
        )
    )

    private fun envelopeWithRawField(base: String, field: String, rawJson: String): String {
        val patched = json.parseToJsonElement(base).jsonObject.toMutableMap()
        patched[field] = json.parseToJsonElement(rawJson)
        return JsonObject(patched).toString()
    }

    private fun defaultTag() = ActivityTags(
        walletId = WalletScope.default,
        activityId = "a1",
        tags = listOf("coffee"),
    )

    private fun knownDevice(customLabel: String? = null) = KnownDevice(
        id = "dev1",
        name = "Trezor",
        path = "usb-1",
        transportType = TransportType.USB,
        label = null,
        model = "Safe 3",
        lastConnectedAt = 1,
        xpubs = mapOf("nativeSegwit" to "zpubNS"),
        customLabel = customLabel,
        walletId = HARDWARE_WALLET_ID,
    )

    private fun preActivityMetadata() = PreActivityMetadata(
        walletId = WalletScope.default,
        paymentId = "p1",
        tags = listOf("coffee"),
        paymentHash = null,
        txId = null,
        address = null,
        isReceive = true,
        feeRate = 1uL,
        isTransfer = false,
        channelId = null,
        createdAt = 1uL,
    )

    private fun stubWalletBackup(
        paykitSdkBackupState: String? = null,
        watchOnlyAccounts: List<WatchOnlyAccountRecord>? = null,
        watchOnlyAccountAllocationState: WatchOnlyAccountAllocationState? = null,
        paykitPaymentState: PaykitPaymentStateBackup? = null,
        usdtWallet: String? = null,
    ) {
        val walletBackup = WalletBackupV1(
            createdAt = 123,
            transfers = emptyList(),
            privatePaykitHighestReservedReceiveIndexByAddressType = mapOf("nativeSegwit" to 5),
            paykitSdkBackupState = paykitSdkBackupState,
            watchOnlyAccounts = watchOnlyAccounts,
            watchOnlyAccountAllocationState = watchOnlyAccountAllocationState,
            paykitPaymentState = paykitPaymentState,
            usdtWallet = usdtWallet,
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
        whenever(activityRepo.activityTagsChanged).thenReturn(MutableStateFlow(0L))
        whenever(pubkyRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitSdkService.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(privatePaykitRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(privatePaykitAddressReservationRepo.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitPaymentProofStore.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitPresentationStore.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paykitAllowanceStore.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(preActivityMetadataRepo.preActivityMetadataChanged).thenReturn(MutableStateFlow(0L))
        whenever(lightningService.syncStatusChanged).thenReturn(MutableSharedFlow())
    }

    @Test
    fun `native restore gate survives malformed connection correction`() = test {
        whenever(keychain.loadString(WalletConnectionRecovery.KEY, 0)).thenReturn("malformed")
        assertTrue(sut.performFullRestoreFromLatestBackup().isFailure)
        verify(keychain).upsertString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name, "")
        verify(vssBackupClient, never()).getObject(any())
    }

    @Test
    fun `legacy USDT restore gate precedes malformed connection correction`() = test {
        whenever(keychain.loadString(WalletConnectionRecovery.KEY, 0)).thenReturn("malformed")
        assertTrue(sut.restoreUsdtRecoveryAfterLegacyMigration().isFailure)
        verify(keychain).upsertString(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name, "")
        verify(vssBackupClient, never()).getObject(any())
    }

    private fun createSut(
        reservationRepo: PrivatePaykitAddressReservationRepo = privatePaykitAddressReservationRepo,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
    ) = BackupRepo(
        context = context,
        ioDispatcher = ioDispatcher,
        cacheStore = cacheStore,
        vssBackupClient = vssBackupClient,
        vssBackupClientLdk = vssBackupClientLdk,
        settingsStore = settingsStore,
        widgetsStore = widgetsStore,
        watchOnlyAccountStore = watchOnlyAccountStore,
        watchOnlyAccountRepo = watchOnlyAccountRepo,
        hwWalletStore = hwWalletStore,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
        coreService = coreService,
        pubkyRepo = pubkyRepo,
        paykitSdkService = paykitSdkService,
        privatePaykitRepo = Provider { privatePaykitRepo },
        privatePaykitAddressReservationRepo = Provider { reservationRepo },
        paykitPaymentProofRepo = Provider { paykitPaymentProofRepo },
        paykitUsdtPaymentRepo = Provider { paykitUsdtPaymentRepo },
        usdtRepo = Provider { usdtRepo },
        paykitPaymentProofStore = paykitPaymentProofStore,
        paykitPaymentRequestRepo = Provider { paykitPaymentRequestRepo },
        paykitPresentationStore = paykitPresentationStore,
        paykitAllowanceStore = paykitAllowanceStore,
        keychain = keychain,
        onchainSendAttemptStore = onchainSendAttemptStore,
        lightningRepo = Provider { lightningRepo },
        vssStoreIdProvider = vssStoreIdProvider,
        preActivityMetadataRepo = preActivityMetadataRepo,
        lightningService = lightningService,
        clock = clock,
        db = db,
        connectionRecovery = connectionRecoveryFixture(settingsStore, keychain),
    )

    private class BackupRepoTestError(message: String) : AppError(message)

    private companion object {
        const val HARDWARE_WALLET_ID = "trezor:abc123"

        /** Past the 10 minute restore gate, counted from the 1 000 ms the clock starts at. */
        const val RESTORE_GATE_EXPIRED_AT = 700_000L

        /** Core-owned slices as written before `walletId` existed. */
        const val LEGACY_ACTIVITIES_JSON = "[]"
        const val LEGACY_TAGS_JSON = """[{"activityId":"a1","tags":["coffee"]}]"""
        const val LEGACY_METADATA_JSON =
            """[{"paymentId":"p1","tags":["coffee"],"isReceive":true,"feeRate":1,""" +
                """"isTransfer":false,"createdAt":1}]"""
    }

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
