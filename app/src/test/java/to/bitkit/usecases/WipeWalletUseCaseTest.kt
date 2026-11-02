package to.bitkit.usecases

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PaykitUsdtPaymentRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.test.forEachCase
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WipeWalletUseCaseTest : BaseUnitTest() {
    private companion object {
        val PAYKIT_SETUP_DURATION = 20.seconds
    }

    private val backupRepo = mock<BackupRepo>()
    private val keychain = mock<Keychain>()
    private val coreService = mock<CoreService>()
    private val usdtRepo = mock<UsdtRepo>()
    private val paykitUsdtPaymentRepo = mock<PaykitUsdtPaymentRepo>()
    private val db = mock<AppDb>()
    private val settingsStore = mock<SettingsStore>()
    private val cacheStore = mock<CacheStore>()
    private val watchOnlyAccountRepo = mock<WatchOnlyAccountRepo>()
    private val widgetsStore = mock<WidgetsStore>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val firebaseMessaging = mock<FirebaseMessaging>()
    private val migrationService = mock<MigrationService>()
    private val paykitSdkService = PaykitSdkService(mock(), keychain, mock(), settingsStore = mock()) { mock() }
    private val privatePaykitRepoProvider = Provider { privatePaykitRepo }

    private lateinit var sut: WipeWalletUseCase

    private var onWipeCalled = false
    private var onSetWalletExistsStateCalled = false

    private val retainedKeys = setOf(Keychain.Key.PIN, Keychain.Key.PIN_ATTEMPTS_REMAINING)

    @Before
    fun setUp() {
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
        whenever(firebaseMessaging.deleteToken()).thenReturn(Tasks.forResult(null))
        onWipeCalled = false
        onSetWalletExistsStateCalled = false

        sut = wipeWalletUseCase()
    }

    private fun wipeWalletUseCase(paykitSdkService: PaykitSdkService = this.paykitSdkService) = WipeWalletUseCase(
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
        privatePaykitAddressReservationRepo = privatePaykitAddressReservationRepo,
        firebaseMessaging = firebaseMessaging,
        migrationService = migrationService,
        paykitSdkService = paykitSdkService,
        connectionRecovery = connectionRecoveryFixture(settingsStore),
    )

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
        inOrder.verify(privatePaykitRepo).closeAndClear()
        inOrder.verify(lightningRepo).wipeStorage(0)
        inOrder.verify(migrationService).setNeedsPostMigrationSync(false)
        inOrder.verify(migrationService).cleanupAfterMigration()
        inOrder.verify(privatePaykitAddressReservationRepo).clear()
        inOrder.verify(pubkyRepo).wipeLocalState()
        inOrder.verify(usdtRepo).wipe(any())
        inOrder.verify(keychain).wipe(retainedKeys)
        inOrder.verify(paykitUsdtPaymentRepo).resetForWipe()
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
        inOrder.verify(keychain).delete(Keychain.Key.PIN.name)
        inOrder.verify(keychain).delete(Keychain.Key.PIN_ATTEMPTS_REMAINING.name)
        inOrder.verify(keychain).resetEncryptionKey()
        inOrder.verify(keychain).deleteWipePending()
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
        verify(keychain, never()).wipe(any())
        verify(db, never()).clearAllTables()
        assertFalse(onWipeCalled)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should continue when address reservation clear fails`() = runTest {
        whenever { privatePaykitAddressReservationRepo.clear() }.thenThrow(RuntimeException("clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isSuccess)
        verify(keychain).wipe(retainedKeys)
        assertTrue(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should stop before settings reset and keep PIN and wipe-pending flag when keychain wipe fails`() =
        runTest {
            whenever(keychain.wipe(any())).thenThrow(RuntimeException("keystore write failed"))

            val result = sut.invoke(
                resetWalletState = { onWipeCalled = true },
                onSuccess = { onSetWalletExistsStateCalled = true },
            )

            assertEquals(WipeStep.KEYCHAIN, assertIs<WipeStepFailed>(result.exceptionOrNull()).step)
            verify(lightningRepo).wipeStorage(0)
            verify(settingsStore, never()).reset()
            verify(db, never()).clearAllTables()
            verify(migrationService, never()).markMigrationChecked()
            verify(keychain, never()).delete(any())
            verify(keychain, never()).resetEncryptionKey()
            verify(keychain, never()).deleteWipePending()
            assertFalse(onWipeCalled)
            assertFalse(onSetWalletExistsStateCalled)
        }

    @Test
    fun `invoke should retain PIN, attempts and WIPE_PENDING in the bulk keychain wipe`() = runTest {
        val result = sut.invoke(resetWalletState = {}, onSuccess = {})

        assertTrue(result.isSuccess)
        verify(keychain).wipe(retainedKeys)
        verify(keychain, never()).wipe(emptySet())
    }

    @Test
    fun `invoke should delete PIN and attempts, then reset the encryption key, then clear WIPE_PENDING last`() =
        runTest {
            val result = sut.invoke(
                resetWalletState = { onWipeCalled = true },
                onSuccess = { onSetWalletExistsStateCalled = true },
            )

            assertTrue(result.isSuccess)
            val inOrder = inOrder(keychain, settingsStore, migrationService)
            inOrder.verify(keychain).wipe(retainedKeys)
            inOrder.verify(settingsStore).reset()
            inOrder.verify(migrationService).markMigrationChecked()
            inOrder.verify(keychain).delete(Keychain.Key.PIN.name)
            inOrder.verify(keychain).delete(Keychain.Key.PIN_ATTEMPTS_REMAINING.name)
            inOrder.verify(keychain).resetEncryptionKey()
            inOrder.verify(keychain).deleteWipePending()
            inOrder.verifyNoMoreInteractions()
            assertTrue(onSetWalletExistsStateCalled)
        }

    @Test
    fun `invoke should keep the PIN and wipe-pending flag when settings reset fails`() = runTest {
        whenever(settingsStore.reset()).thenThrow(RuntimeException("settings write failed"))

        val result = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertEquals(WipeStep.SETTINGS, assertIs<WipeStepFailed>(result.exceptionOrNull()).step)
        verify(keychain).wipe(retainedKeys)
        verify(keychain, never()).delete(any())
        verify(keychain, never()).deleteWipePending()
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should keep the wipe-pending flag when deleting the PIN fails`() = runTest {
        whenever(keychain.delete(Keychain.Key.PIN.name)).thenThrow(RuntimeException("delete failed"))

        val result = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertEquals(WipeStep.PIN, assertIs<WipeStepFailed>(result.exceptionOrNull()).step)
        verify(keychain, never()).resetEncryptionKey()
        verify(keychain, never()).deleteWipePending()
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should report the commit step when clearing the wipe-pending flag fails`() = runTest {
        whenever(keychain.deleteWipePending()).thenThrow(RuntimeException("delete failed"))

        val result = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertEquals(WipeStep.COMMIT, assertIs<WipeStepFailed>(result.exceptionOrNull()).step)
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should finish and report incomplete when only the USDT storage wipe fails`() = runTest {
        whenever(usdtRepo.wipe(any())).doSuspendableAnswer {
            it.getArgument<suspend () -> Unit>(0).invoke()
            Result.failure<Unit>(RuntimeException("usdt files locked"))
        }

        val result = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertIs<WipeIncomplete>(result.exceptionOrNull())
        verify(keychain).wipe(retainedKeys)
        verify(settingsStore).reset()
        verify(keychain).deleteWipePending()
        assertFalse(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should continue when FCM token deletion or remote cleanup times out`() = runTest {
        whenever(pubkyRepo.removeBitkitPaymentEndpoints()).doSuspendableAnswer { awaitCancellation() }
        whenever(firebaseMessaging.deleteToken()).thenReturn(TaskCompletionSource<Void>().task)

        val result = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertTrue(result.isSuccess)
        verify(privatePaykitRepo).closeAndClear()
        verify(lightningRepo).wipeStorage(0)
        verify(keychain).deleteWipePending()
        assertTrue(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke should reach the commit step when Paykit setup outlives the wipe's wait for it`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.initialize()).doSuspendableAnswer {
            delay(PAYKIT_SETUP_DURATION)
            IdentityStatus(null, PubkyIdentityCapability.SIGNED_OUT)
        }
        val paykitSdkService = PaykitSdkService(
            mock(),
            keychain,
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            platformInitializer = {},
            settingsStore = mock(),
            sdkFactory = { sdk },
        )
        whenever(pubkyRepo.removeBitkitPaymentEndpoints()).doSuspendableAnswer {
            runSuspendCatching { paykitSdkService.syncPublicEndpoints(emptyList()) }.map {}
        }
        whenever(pubkyRepo.wipeLocalState()).doSuspendableAnswer {
            runSuspendCatching { paykitSdkService.forgetSessionAccess() }
            Unit
        }

        val result = wipeWalletUseCase(paykitSdkService = paykitSdkService)
            .invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

        assertTrue(result.isSuccess)
        verify(lightningRepo).wipeStorage(0)
        verify(keychain).wipe(retainedKeys)
        verify(keychain).deleteWipePending()
        assertTrue(onSetWalletExistsStateCalled)
    }

    @Test
    fun `invoke resumed after each step completes`() = runTest {
        val killPoints = listOf(
            killPoint("remote cleanup") { doThrow(it).whenever(pubkyRepo).removeBitkitPaymentEndpoints() },
            killPoint("LDK storage") { doThrow(it).whenever(lightningRepo).wipeStorage(0) },
            killPoint("keychain") { doThrow(it).whenever(keychain).wipe(retainedKeys) },
            killPoint("core data") { doThrow(it).whenever(coreService).wipeData() },
            killPoint("settings") { doThrow(it).whenever(settingsStore).reset() },
            killPoint("migration checked") { doThrow(it).whenever(migrationService).markMigrationChecked() },
            killPoint("PIN") { doThrow(it).whenever(keychain).delete(Keychain.Key.PIN.name) },
            killPoint("attempts") { doThrow(it).whenever(keychain).delete(Keychain.Key.PIN_ATTEMPTS_REMAINING.name) },
            killPoint("encryption key") { doThrow(it).whenever(keychain).resetEncryptionKey() },
            killPoint("commit") { doThrow(it).whenever(keychain).deleteWipePending() },
        )
        val killedAfterPinDeletion = setOf("attempts", "encryption key", "commit")

        killPoints.forEachCase({ it.first }) { (name, killAt) ->
            var isPinPresent = true
            var isFlagPresent = true
            val freshProcess = {
                reset(keychain, coreService, settingsStore, migrationService, pubkyRepo, lightningRepo)
                setUp()
                whenever { keychain.delete(Keychain.Key.PIN.name) }.doSuspendableAnswer { isPinPresent = false }
                whenever { keychain.deleteWipePending() }.doSuspendableAnswer { isFlagPresent = false }
            }

            freshProcess()
            killAt(CancellationException("killed at '$name'"))
            assertFailsWith<CancellationException> { sut.invoke(resetWalletState = {}, onSuccess = {}) }

            assertTrue(isFlagPresent, name)
            assertEquals(name in killedAfterPinDeletion, !isPinPresent, name)

            freshProcess()
            val resumed = sut.invoke(resetWalletState = {}, onSuccess = { onSetWalletExistsStateCalled = true })

            assertTrue(resumed.isSuccess, name)
            assertFalse(isPinPresent, name)
            assertFalse(isFlagPresent, name)
            val inOrder = inOrder(keychain, settingsStore, migrationService)
            inOrder.verify(keychain).wipe(retainedKeys)
            inOrder.verify(settingsStore).reset()
            inOrder.verify(migrationService).markMigrationChecked()
            inOrder.verify(keychain).delete(Keychain.Key.PIN.name)
            inOrder.verify(keychain).delete(Keychain.Key.PIN_ATTEMPTS_REMAINING.name)
            inOrder.verify(keychain).resetEncryptionKey()
            inOrder.verify(keychain).deleteWipePending()
        }
    }

    private fun killPoint(name: String, stub: suspend (Throwable) -> Unit) = name to stub

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
        whenever(keychain.wipe(any())).thenThrow(CancellationException("Cancelled"))

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
        verify(pubkyRepo).wipeLocalState()
        verify(keychain).wipe(retainedKeys)
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
        verify(privatePaykitRepo).closeAndClear()
        verify(pubkyRepo).wipeLocalState()
        verify(keychain).wipe(retainedKeys)
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
        verify(privatePaykitRepo).closeAndClear()
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
        inOrder.verify(keychain).wipe(retainedKeys)
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
        verify(keychain).wipe(retainedKeys)
        assertTrue(onWipeCalled)
        assertTrue(onSetWalletExistsStateCalled)
    }
}
