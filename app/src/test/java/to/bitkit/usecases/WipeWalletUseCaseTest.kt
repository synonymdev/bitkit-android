package to.bitkit.usecases

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.test.BaseUnitTest
import javax.inject.Provider
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WipeWalletUseCaseTest : BaseUnitTest() {

    private val backupRepo = mock<BackupRepo>()
    private val keychain = mock<Keychain>()
    private val coreService = mock<CoreService>()
    private val db = mock<AppDb>()
    private val settingsStore = mock<SettingsStore>()
    private val cacheStore = mock<CacheStore>()
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
    private val privatePaykitRepoProvider = Provider { privatePaykitRepo }

    private lateinit var sut: WipeWalletUseCase

    private var onWipeCalled = false
    private var onSetWalletExistsStateCalled = false

    @Before
    fun setUp() {
        whenever { lightningRepo.wipeStorage(0) }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.removeBitkitPaymentEndpoints() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.closeAndClear() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitAddressReservationRepo.clear() }.thenReturn(Unit)
        onWipeCalled = false
        onSetWalletExistsStateCalled = false

        sut = WipeWalletUseCase(
            backupRepo = backupRepo,
            keychain = keychain,
            coreService = coreService,
            db = db,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
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
        )
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
            db,
            settingsStore,
            cacheStore,
            widgetsStore,
            blocktankRepo,
            activityRepo,
            hwWalletRepo,
            lightningRepo,
            pubkyRepo,
            privatePaykitRepo,
            privatePaykitAddressReservationRepo,
        )
        inOrder.verify(backupRepo).setWiping(true)
        inOrder.verify(backupRepo).reset()
        inOrder.verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
        inOrder.verify(pubkyRepo).removeBitkitPaymentEndpoints()
        inOrder.verify(privatePaykitRepo).closeAndClear()
        inOrder.verify(privatePaykitAddressReservationRepo).clear()
        inOrder.verify(pubkyRepo).wipeLocalState()
        inOrder.verify(keychain).wipe()
        inOrder.verify(coreService).wipeData()
        inOrder.verify(db).clearAllTables()
        inOrder.verify(settingsStore).reset()
        inOrder.verify(cacheStore).reset()
        inOrder.verify(widgetsStore).reset()
        inOrder.verify(blocktankRepo).resetState()
        inOrder.verify(activityRepo).resetState()
        inOrder.verify(hwWalletRepo).resetState()
        assertTrue(onWipeCalled)
        inOrder.verify(lightningRepo).wipeStorage(0)
        assertTrue(onSetWalletExistsStateCalled)
        inOrder.verify(backupRepo).setWiping(false)
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
        whenever(keychain.wipe()).thenThrow(RuntimeException("Test error"))

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
        verify(pubkyRepo).wipeLocalState()
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
        verify(privatePaykitRepo).closeAndClear()
        verify(pubkyRepo).wipeLocalState()
        verify(keychain).wipe()
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should return failure when lightningRepo wipeStorage fails`() = runTest {
        val error = RuntimeException("Lightning wipe failed")
        whenever { lightningRepo.wipeStorage(0) }.thenReturn(Result.failure(error))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isFailure)
        verify(backupRepo).setWiping(false)
    }

    @Test
    fun `invoke should return failure when database clear fails`() = runTest {
        whenever(db.clearAllTables()).thenThrow(RuntimeException("DB clear failed"))

        val result = sut.invoke(
            resetWalletState = { onWipeCalled = true },
            onSuccess = { onSetWalletExistsStateCalled = true },
        )

        assertTrue(result.isFailure)
        verify(backupRepo).setWiping(false)
    }
}
