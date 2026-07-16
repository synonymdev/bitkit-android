package to.bitkit.repositories

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.lightningdevkit.ldknode.AddressType
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.OnchainPayment
import org.lightningdevkit.ldknode.OnchainWalletAccount
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountReconciliationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WatchOnlyAccountXpubSerializer
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.services.WatchOnlyAccountLifecycleCoordinator
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchOnlyAccountLifecycleCoordinatorTest : BaseUnitTest() {
    @Test
    fun `reconciliation cannot remove an account while authorization is being persisted`() = test {
        val account = account().copy(isTrackingEnabled = false, setupState = WatchOnlyAccountSetupState.PendingDelivery)
        var storedAccounts = listOf(account)
        val tracked = AtomicBoolean(false)
        val loadCount = AtomicInteger(0)
        val authorizationSyncStarted = CountDownLatch(1)
        val allowAuthorizationSync = CountDownLatch(1)
        val store = mock<WatchOnlyAccountStore>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        val coordinator = WatchOnlyAccountLifecycleCoordinator()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer {
            loadCount.incrementAndGet()
            storedAccounts
        }
        whenever(store.loadReconciliationState()).thenAnswer {
            loadCount.incrementAndGet()
            WatchOnlyAccountReconciliationState(storedAccounts, emptyList())
        }
        whenever(store.update(any())).thenAnswer {
            val transform = it.getArgument<(List<WatchOnlyAccountRecord>) -> List<WatchOnlyAccountRecord>>(0)
            storedAccounts = transform(storedAccounts)
            Unit
        }
        whenever(node.listOnchainWalletAccounts()).thenAnswer {
            if (tracked.get()) listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)) else emptyList()
        }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer { tracked.set(true) }.whenever(node)
            .addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        doAnswer { tracked.set(false) }.whenever(node)
            .removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        whenever(node.syncWallets()).thenAnswer {
            authorizationSyncStarted.countDown()
            check(allowAuthorizationSync.await(5, TimeUnit.SECONDS))
            Unit
        }
        val lightningService = lightningService(store, node, coordinator)
        val sut = repository(store, lightningService, coordinator)

        val authorization = launch { sut.beginAuthorization(account.id) }
        assertTrue(authorizationSyncStarted.await(5, TimeUnit.SECONDS))

        val reconciliation = launch {
            lightningService.reconcileWatchOnlyAccounts(syncAfterReconcile = false)
        }
        runCurrent()

        assertEquals(1, loadCount.get())
        assertTrue(reconciliation.isActive)

        allowAuthorizationSync.countDown()
        authorization.join()
        reconciliation.join()

        assertTrue(tracked.get())
        assertTrue(storedAccounts.single().isTrackingEnabled)
        assertEquals(WatchOnlyAccountSetupState.Authorizing, storedAccounts.single().setupState)
        assertEquals(2, loadCount.get())
        verify(node, never()).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
    }

    @Test
    fun `restore waits for in-flight reconciliation before replacing persisted accounts`() = test {
        val account = account()
        val restoredAccount = account.copy(name = "Restored account")
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to account.accountIndex),
        )
        val reconciliationStarted = CountDownLatch(1)
        val allowReconciliation = CountDownLatch(1)
        val store = mock<WatchOnlyAccountStore>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        val coordinator = WatchOnlyAccountLifecycleCoordinator()
        whenever(store.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(listOf(account), emptyList()),
        )
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, account.accountIndex.toUInt())),
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer {
            reconciliationStarted.countDown()
            check(allowReconciliation.await(5, TimeUnit.SECONDS))
        }.whenever(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            account.accountIndex.toUInt(),
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        val lightningService = lightningService(store, node, coordinator)
        val sut = repository(store, lightningService, coordinator)

        val reconciliation = launch {
            lightningService.reconcileWatchOnlyAccounts(syncAfterReconcile = false)
        }
        assertTrue(reconciliationStarted.await(5, TimeUnit.SECONDS))

        val restore = launch { sut.restore(listOf(restoredAccount), allocationState) }
        runCurrent()
        verify(store, never()).restore(listOf(restoredAccount), allocationState)

        allowReconciliation.countDown()
        reconciliation.join()
        restore.join()

        verify(store).restore(listOf(restoredAccount), allocationState)
    }

    private fun lightningService(
        store: WatchOnlyAccountStore,
        node: Node,
        coordinator: WatchOnlyAccountLifecycleCoordinator,
    ) = LightningService(
        bgDispatcher = testDispatcher,
        keychain = mock<Keychain>(),
        vssStoreIdProvider = mock<VssStoreIdProvider>(),
        settingsStore = mock<SettingsStore>(),
        watchOnlyAccountStore = store,
        loggerLdk = mock<LoggerLdk>(),
        watchOnlyAccountLifecycleCoordinator = coordinator,
    ).apply { this.node = node }

    private fun repository(
        store: WatchOnlyAccountStore,
        lightningService: LightningService,
        coordinator: WatchOnlyAccountLifecycleCoordinator,
    ) = WatchOnlyAccountRepo(
        testDispatcher,
        store,
        lightningService,
        coordinator,
        mock<WatchOnlyAccountXpubSerializer>(),
    )

    private fun account() = WatchOnlyAccountRecord(
        id = "account-id",
        walletIndex = 0,
        accountIndex = 1,
        addressType = WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE,
        xpub = "xpub",
        requestFingerprint = "request",
        createdAt = 1,
        name = "Creator account",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Active,
    )
}
