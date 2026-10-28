package to.bitkit.usecases

import androidx.datastore.core.DataStoreFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationSerializer
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.data.PubkyImageCacheEpoch
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PaykitUsdtPaymentRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import java.util.UUID
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class WipeWalletUseCaseTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()

    private val backupRepo = mock<BackupRepo>()
    private val keychain = mock<Keychain>()
    private val coreService = mock<CoreService>()
    private val usdtRepo = mock<UsdtRepo>()
    private val paykitUsdtPaymentRepo = mock<PaykitUsdtPaymentRepo>()
    private val db = mock<AppDb>()
    private val settingsStore = mock<SettingsStore>()
    private val connectionRecovery = connectionRecoveryFixture(settingsStore)
    private val cacheStore = mock<CacheStore>()
    private val watchOnlyAccountRepo = mock<WatchOnlyAccountRepo>()
    private val widgetsStore = mock<WidgetsStore>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val profileRemovalStore = ProfileRemovalStore.inMemoryForTesting()
    private val contactPaymentSettingsRepo = mock<ContactPaymentSettingsRepo>()
    private val profilePause = PrivatePaykitRepo.ProfileDeletionPause()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val firebaseMessaging = mock<FirebaseMessaging>()
    private val migrationService = mock<MigrationService>()
    private val paykitSdkService = PaykitSdkService(
        mock(),
        keychain,
        mock(),
        settingsStore = mock(),
        profileRemovalStore = profileRemovalStore,
    ) { mock() }
    private val privatePaykitRepoProvider = Provider { privatePaykitRepo }

    private lateinit var sut: WipeWalletUseCase

    private var onWipeCalled = false
    private var onSetWalletExistsStateCalled = false

    @Before
    fun setUp() {
        whenever { contactPaymentSettingsRepo.withWalletWipe<Unit>(any(), any()) }.doSuspendableAnswer {
            val admit = it.getArgument<suspend (suspend () -> Unit) -> Unit>(0)
            admit(it.getArgument(1))
        }
        whenever { privatePaykitRepo.withProfileDeletionPaused<Unit>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend (PrivatePaykitRepo.ProfileDeletionPause) -> Unit>(0).invoke(profilePause)
        }
        whenever { privatePaykitRepo.finishProfileRemoval(any()) }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.withWalletWipeAdmission<Unit>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> Unit>(0).invoke()
        }
        whenever { lightningRepo.stop() }.thenReturn(Result.success(Unit))
        whenever { usdtRepo.wipe(any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> Unit>(0).invoke()
            Result.success(Unit)
        }
        whenever { lightningRepo.wipeStorage(0) }.thenReturn(Result.success(Unit))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever { pubkyRepo.removeBitkitPaymentEndpoints() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.closeAndClear() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitAddressReservationRepo.clear() }.thenReturn(Unit)
        onWipeCalled = false
        onSetWalletExistsStateCalled = false

        sut = createSut()
    }

    private fun createSut(
        pubkyRepo: PubkyRepo = this.pubkyRepo,
        sharingRepo: ContactPaymentSettingsRepo = contactPaymentSettingsRepo,
        reservations: PrivatePaykitAddressReservationRepo = privatePaykitAddressReservationRepo,
    ) = WipeWalletUseCase(
        backupRepo = backupRepo,
        keychain = keychain,
        coreService = coreService,
        usdtRepo = usdtRepo,
        paykitUsdtPaymentRepo = paykitUsdtPaymentRepo,
        db = db,
        settingsStore = settingsStore,
        cacheStore = cacheStore,
        watchOnlyAccountRepo = watchOnlyAccountRepo,
        widgetsStore = widgetsStore,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
        hwWalletRepo = hwWalletRepo,
        lightningRepo = lightningRepo,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepoProvider,
        privatePaykitAddressReservationRepo = reservations,
        firebaseMessaging = firebaseMessaging,
        migrationService = migrationService,
        paykitSdkService = paykitSdkService,
        profileRemovalStore = profileRemovalStore,
        contactPaymentSettingsRepo = Provider { sharingRepo },
        connectionRecovery = connectionRecovery,
    )

    private fun realPubkyRepo(service: PubkyService, shared: SharedPubkyClient = mock()): PubkyRepo {
        whenever { service.requireWalletWipe() }.doSuspendableAnswer { paykitSdkService.requireWalletWipe() }
        whenever { service.observedContactRecords(any()) }.doSuspendableAnswer { service.contactRecords() }
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(MutableStateFlow(PubkyStoreData()))
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        whenever(settingsStore.isPubkyProfileSetupPending).thenReturn(MutableStateFlow(false))
        return PubkyRepo(
            ioDispatcher = testDispatcher,
            pubkyService = service,
            keychain = keychain,
            sharedPubkyClient = shared,
            imageLoader = mock(),
            imageCacheEpoch = PubkyImageCacheEpoch(),
            pubkyStore = store,
            settingsStore = settingsStore,
            httpClient = mock(),
            clock = Clock.System,
            profileRemovalStore = profileRemovalStore,
        )
    }

    @Test
    fun `wallet wipe drains the queued receipt before SDK cleanup and credential deletion`() = test {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejected = CompletableDeferred<Boolean>()
        var receiptWritten = false
        val receipt = launch {
            connectionRecovery.withReceiptOperation { owner ->
                ServiceQueue.CORE.background {
                    entered.complete(Unit)
                    release.await()
                    rejected.complete(
                        runCatching { owner.commitReceiptState { receiptWritten = true } }
                            .exceptionOrNull() is CancellationException
                    )
                }
            }
        }
        entered.await()
        val wipe = async {
            sut.invoke(
                resetWalletState = { onWipeCalled = true },
                onSuccess = { onSetWalletExistsStateCalled = true },
            )
        }
        runCurrent()
        verify(keychain, never()).wipe()
        verify(privatePaykitRepo, never()).finishProfileRemoval(any())
        verify(lightningRepo, never()).stop()
        assertFalse(wipe.isCompleted)
        release.complete(Unit)
        assertTrue(rejected.await())
        receipt.join()
        assertTrue(wipe.await().isSuccess)
        assertFalse(receiptWritten)
        verify(keychain).wipe()
        assertTrue(onWipeCalled)
        assertTrue(onSetWalletExistsStateCalled)
        assertNull(profileRemovalStore.pendingAttempt())
    }

    @Test
    fun `wipe drains real repository removal and noncancellable local cleanup before SDK reset`() = test {
        val service = mock<PubkyService>()
        whenever { service.withProfileRemoval<Unit>(any(), any()) }.doSuspendableAnswer {
            paykitSdkService.withProfileRemoval(it.getArgument(0), it.getArgument(1))
        }
        val repo = realPubkyRepo(service)
        val sharing = ContactPaymentSettingsRepo(settingsStore, mock(), privatePaykitRepo, repo, testDispatcher)
        advanceUntilIdle()
        paykitSdkService.initialize()
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val removed = launch {
            sharing.withProfileRemoval {
                repo.performProfileRemoval(repo.profileOwner()) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            }
        }
        withTimeout(5_000) { entered.await() }
        val wipe = async {
            withTimeout(5_000) { createSut(repo, sharing).invoke(resetWalletState = {}, onSuccess = {}) }
        }
        try {
            cleanupStarted.await()
            runCurrent()
            assertFalse(wipe.isCompleted)
            verify(backupRepo, never()).reset()
            verify(keychain, never()).wipe()
        } finally {
            releaseCleanup.complete(Unit)
        }
        removed.join()
        assertTrue(wipe.await().isSuccess)
        verify(keychain).wipe()
    }

    @Test
    fun `wipe drains real Ring adoption local tail before entering SDK wipe barrier`() = test {
        val service = mock<PubkyService>()
        val shared = mock<SharedPubkyClient>()
        val rawKey = "5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        whenever { shared.ringCredential(rawKey) }.thenReturn(Result.success("ring_secret"))
        whenever { service.publicKeyFromSecret("ring_secret") }.thenReturn(rawKey)
        val entered = CompletableDeferred<Unit>()
        val releaseContacts = CompletableDeferred<Unit>()
        whenever { service.contactRecords() }.doSuspendableAnswer {
            entered.complete(Unit)
            releaseContacts.await()
            emptyList()
        }
        val repo = realPubkyRepo(service, shared)
        advanceUntilIdle()
        paykitSdkService.initialize()
        val adoption = async { repo.adoptRingIdentity(rawKey) }
        withTimeout(5_000) { entered.await() }
        val wipe = async {
            withTimeout(5_000) { createSut(repo).invoke(resetWalletState = {}, onSuccess = {}) }
        }
        try {
            runCurrent()
            assertFalse(wipe.isCompleted)
            verify(backupRepo, never()).reset()
            verify(keychain, never()).wipe()
        } finally {
            releaseContacts.complete(Unit)
        }
        adoption.await().getOrThrow()
        wipe.await().getOrThrow()
        verify(keychain).wipe()
    }

    @Test
    fun `wipe replaces unreadable reservations after key acknowledgement before profile cleanup`() = test {
        val file = folder.newFile("reservations").also { it.writeText("{broken") }
        val storage = DataStoreFactory.create(PrivatePaykitReservationSerializer, scope = backgroundScope) { file }
        val store = mock<PrivatePaykitReservationStore>()
        whenever(store.data).thenReturn(storage.data)
        whenever(store.update(any())).doSuspendableAnswer {
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            storage.updateData { current -> transform(current) }
            Unit
        }
        whenever(store.reset()).doSuspendableAnswer {
            verify(keychain).wipe()
            storage.updateData { PrivatePaykitReservationData() }
            Unit
        }
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        val reservations = PrivatePaykitAddressReservationRepo(
            testDispatcher,
            store,
            settingsStore,
            coreService,
            lightningRepo,
        )
        val contact = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq", 1)),
        )
        assertTrue(reservations.currentOrRotatedAddress(contact).isFailure)
        assertFalse(storage.data.first().receiptSourceReadable)
        whenever(privatePaykitRepo.finishProfileRemoval(any())).doSuspendableAnswer {
            assertTrue(storage.data.first().receiptSourceReadable)
            reservations.clearContactAssignments(emptySet())
            Result.success(Unit)
        }
        assertTrue(createSut(reservations = reservations).invoke(resetWalletState = {}, onSuccess = {}).isSuccess)
        assertEquals(PrivatePaykitReservationData(), storage.data.first())
        assertNull(profileRemovalStore.pendingAttempt())
    }

    @Test
    fun `pending restart wipe skips remote cleanup and only clears marker after local acknowledgement`() = test {
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) { profileRemovalStore.begin(id) }
        whenever { privatePaykitRepo.finishProfileRemoval(any()) }.doSuspendableAnswer {
            assertEquals(id, profileRemovalStore.pendingAttempt())
            verify(keychain).wipe()
            Result.success(Unit)
        }
        assertTrue(sut.invoke(resetWalletState = {}, onSuccess = {}).isSuccess)
        assertNull(profileRemovalStore.pendingAttempt())
        verify(privatePaykitRepo, never()).removePublishedEndpointsForCleanup(any())
        verify(pubkyRepo, never()).removeBitkitPaymentEndpoints()
        verify(privatePaykitRepo, never()).closeAndClear()
    }

    @Test
    fun `private persistence failure retains marker and suppresses completion callbacks`() = test {
        whenever {
            privatePaykitRepo.finishProfileRemoval(
                any()
            )
        }.thenReturn(Result.failure(IllegalStateException("disk")))
        assertTrue(
            sut.invoke(
                resetWalletState = { onWipeCalled = true },
                onSuccess = { onSetWalletExistsStateCalled = true }
            ).isFailure
        )
        verify(keychain).wipe()
        assertNotNull(profileRemovalStore.pendingAttempt())
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `profile metadata failure retains recovery after credentials were wiped`() = test {
        val failure = IllegalStateException("profile persistence unavailable")
        whenever { pubkyRepo.finishProfileWipeLocally() }.thenThrow(failure)

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertEquals(failure.message, assertIs<WipeIncomplete>(result.exceptionOrNull()).cause?.message)
        verify(keychain).wipe()
        verify(privatePaykitRepo).finishProfileRemoval(profilePause)
        verify(privatePaykitAddressReservationRepo).clear()
        assertNotNull(profileRemovalStore.pendingAttempt())
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `settings failure after credential teardown retains recovery without success`() = test {
        val failure = IllegalStateException("settings persistence unavailable")
        whenever { settingsStore.reset() }.thenThrow(failure)

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertEquals(failure.message, assertIs<WipeIncomplete>(result.exceptionOrNull()).cause?.message)
        verify(keychain).wipe()
        verify(pubkyRepo).finishProfileWipeLocally()
        verify(privatePaykitAddressReservationRepo).clear()
        assertNotNull(profileRemovalStore.pendingAttempt())
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should reset all app state in correct order`() = runTest {
        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        val inOrder = inOrder(
            backupRepo,
            keychain,
            coreService,
            usdtRepo,
            paykitUsdtPaymentRepo,
            db,
            settingsStore,
            cacheStore,
            watchOnlyAccountRepo,
            widgetsStore,
            blocktankRepo,
            activityRepo,
            hwWalletRepo,
            lightningRepo,
            pubkyRepo,
            privatePaykitRepo,
            privatePaykitAddressReservationRepo,
            migrationService,
        )
        inOrder.verify(backupRepo).setWiping(true)
        inOrder.verify(lightningRepo).setWiping(true)
        inOrder.verify(backupRepo).reset()
        inOrder.verify(lightningRepo).stop()
        inOrder.verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
        inOrder.verify(pubkyRepo).removeBitkitPaymentEndpoints()
        inOrder.verify(lightningRepo).wipeStorage(0)
        inOrder.verify(migrationService).setNeedsPostMigrationSync(false)
        inOrder.verify(migrationService).cleanupAfterMigration()
        inOrder.verify(usdtRepo).wipe(any())
        inOrder.verify(keychain).wipe()
        inOrder.verify(paykitUsdtPaymentRepo).resetForWipe()
        inOrder.verify(privatePaykitAddressReservationRepo).clear()
        inOrder.verify(privatePaykitRepo).finishProfileRemoval(profilePause)
        inOrder.verify(pubkyRepo).finishProfileWipeLocally()
        inOrder.verify(coreService).wipeData()
        inOrder.verify(db).clearAllTables()
        inOrder.verify(settingsStore).reset()
        inOrder.verify(cacheStore).reset()
        inOrder.verify(watchOnlyAccountRepo).clear()
        inOrder.verify(widgetsStore).reset()
        inOrder.verify(blocktankRepo).resetState()
        inOrder.verify(activityRepo).resetState()
        inOrder.verify(hwWalletRepo).resetState()
        inOrder.verify(migrationService).markMigrationChecked()
        assertTrue(onWipeCalled)
        assertTrue(onSetWalletExistsStateCalled)
        inOrder.verify(lightningRepo).setWiping(false)
        inOrder.verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should reject a second wipe while one is in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        whenever { lightningRepo.stop() }.doSuspendableAnswer {
            release.await()
            Result.success(Unit)
        }
        var first: Result<Unit>? = null
        val job = launch { first = sut.invoke(resetWalletState = {}, onSuccess = {}) }
        runCurrent()

        val second = sut.invoke(resetWalletState = {}, onSuccess = {})

        assertIs<WipeAlreadyInProgress>(second.exceptionOrNull())
        release.complete(Unit)
        job.join()
        assertTrue(requireNotNull(first).isSuccess)
        verify(lightningRepo).stop()
    }

    @Test
    fun `invoke should fail without wiping and restart observers when node stop fails while running`() = runTest {
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)))
        whenever { lightningRepo.stop() }.thenReturn(Result.failure(RuntimeException("stop failed")))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isFailure)
        val inOrder = inOrder(backupRepo)
        inOrder.verify(backupRepo).setWiping(false)
        inOrder.verify(backupRepo).startObservingBackups()
        verify(lightningRepo, never()).wipeStorage(any())
        verify(keychain, never()).wipe()
        verify(db, never()).clearAllTables()
        assertNull(profileRemovalStore.pendingAttempt())
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `cancellation while stopping preserves prior recovery scope without arming a fresh wipe`() = test {
        for (pending in listOf(null, UUID.randomUUID().toString())) {
            if (pending != null) profileRemovalStore.withAccess(pending) { profileRemovalStore.begin(pending) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            whenever(lightningRepo.stop()).doSuspendableAnswer {
                entered.complete(Unit)
                release.await()
                Result.failure(CancellationException("Node stop cancelled"))
            }
            val wiping = launch { sut.invoke(resetWalletState = {}, onSuccess = {}) }
            entered.await()
            wiping.cancel()
            release.complete(Unit)
            wiping.join()
            assertEquals(pending, profileRemovalStore.pendingAttempt())
            // A failed pre-stop wipe must not upgrade an existing PROFILE obligation.
            if (pending != null) profileRemovalStore.withAccess(pending) { profileRemovalStore.complete(pending) }
            connectionRecovery.withReceiptOperation { it.requireCurrent() }
        }
        verify(keychain, never()).wipe()
        verify(lightningRepo, never()).wipeStorage(any())
    }

    @Test
    fun `invoke retains pending recovery when address reservation clear fails`() = runTest {
        whenever { privatePaykitAddressReservationRepo.clear() }.thenThrow(RuntimeException("clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertEquals("clear failed", assertIs<WipeIncomplete>(result.exceptionOrNull()).cause?.message)
        verify(keychain).wipe()
        assertFalse(onSetWalletExistsStateCalled)
        assertFalse(onWipeCalled)
        assertNotNull(profileRemovalStore.pendingAttempt())
    }

    @Test
    fun `invoke retains private safety state and recovery when keychain wipe fails`() = runTest {
        val error = RuntimeException("keystore write failed")
        whenever(keychain.wipe()).thenThrow(error)

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        val failure = assertIs<WipeIncomplete>(result.exceptionOrNull())
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === error })
        verify(lightningRepo).wipeStorage(0)
        verify(db, never()).clearAllTables()
        verify(settingsStore, never()).reset()
        assertFalse(onWipeCalled)
        verify(privatePaykitRepo, never()).finishProfileRemoval(any())
        verify(privatePaykitAddressReservationRepo, never()).clear()
        assertNotNull(profileRemovalStore.pendingAttempt())
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should pass walletIndex to lightningRepo wipeStorage`() = runTest {
        val walletIndex = 5
        whenever { lightningRepo.wipeStorage(walletIndex) }.thenReturn(Result.success(Unit))

        val result = sut.invoke(
            walletIndex = walletIndex,
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(lightningRepo).wipeStorage(walletIndex)
    }

    @Test
    fun `invoke should set wiping to false even on failure`() = runTest {
        whenever { lightningRepo.stop() }.thenReturn(Result.failure(RuntimeException("Test error")))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isFailure)
        verify(backupRepo).setWiping(true)
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should set wiping to false when cancelled`() = runTest {
        whenever(keychain.wipe()).thenThrow(CancellationException("Cancelled"))

        assertFailsWith<CancellationException> {
            sut.invoke(
                resetWalletState = { onWipeCalled = true },
                onSuccess = { onSetWalletExistsStateCalled = true },
            )
        }

        verify(backupRepo).setWiping(true)
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should continue when endpoint cleanup fails`() = runTest {
        whenever { pubkyRepo.removeBitkitPaymentEndpoints() }.thenReturn(
            Result.failure(RuntimeException("Cleanup failed")),
        )

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(pubkyRepo).finishProfileWipeLocally()
        verify(keychain).wipe()
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should continue when private endpoint cleanup fails`() = runTest {
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }.thenReturn(
            Result.failure(RuntimeException("Private cleanup failed")),
        )

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(privatePaykitRepo).finishProfileRemoval(profilePause)
        verify(pubkyRepo).finishProfileWipeLocally()
        verify(keychain).wipe()
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should fail before wiping local state when LDK storage wipe fails`() = runTest {
        val error = RuntimeException("Lightning wipe failed")
        whenever { lightningRepo.wipeStorage(0) }.thenReturn(Result.failure(error))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isFailure)
        verify(privatePaykitRepo, never()).finishProfileRemoval(any())
        verify(keychain, never()).wipe()
        verify(db, never()).clearAllTables()
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should clear migration data before wiping the keychain`() = runTest {
        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        val inOrder = inOrder(migrationService, keychain)
        inOrder.verify(migrationService).setNeedsPostMigrationSync(false)
        inOrder.verify(migrationService).cleanupAfterMigration()
        inOrder.verify(keychain).wipe()
    }

    @Test
    fun `invoke should mark migration checked when migration data clear fails`() = runTest {
        whenever { migrationService.cleanupAfterMigration() }.thenThrow(RuntimeException("clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(migrationService).markMigrationChecked()
        assertTrue(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should clear post-migration sync flag even when migration data clear fails`() = runTest {
        whenever { migrationService.cleanupAfterMigration() }.thenThrow(RuntimeException("clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(migrationService).setNeedsPostMigrationSync(false)
        verify(migrationService).markMigrationChecked()
    }

    @Test
    fun `invoke should clear migration data and finish when post-migration sync flag clear fails`() = runTest {
        whenever { migrationService.setNeedsPostMigrationSync(false) }.thenThrow(RuntimeException("flag failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(migrationService).cleanupAfterMigration()
        verify(migrationService).markMigrationChecked()
        assertTrue(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should complete the wipe when database clear fails`() = runTest {
        whenever(db.clearAllTables()).thenThrow(RuntimeException("DB clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(settingsStore).reset()
        verify(keychain).wipe()
        assertTrue(onWipeCalled)
        assertTrue(onSetWalletExistsStateCalled)
    }
}
