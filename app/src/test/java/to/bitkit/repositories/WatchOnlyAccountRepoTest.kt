package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountReconciliationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.reserveAccountIndex
import to.bitkit.data.restoreAccounts
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchOnlyAccountRepoTest : BaseUnitTest() {
    @Test
    fun `authorization fails before tracking when the prepared account is missing`() = test {
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData()))
        whenever(store.load()).thenReturn(emptyList())

        assertFailsWith<WatchOnlyAccountError.AuthorizationAccountMissing> {
            sut.beginAuthorization("missing")
        }

        verify(lightningService, never()).node
        verify(store, never()).update(any())
    }

    @Test
    fun `activation fails before persistence when the account is missing`() = test {
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData()))
        whenever(store.load()).thenReturn(emptyList())
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        assertFailsWith<WatchOnlyAccountError.AuthorizationAccountMissing> {
            sut.markActive("missing")
        }

        verify(store, never()).markActive(any())
    }

    @Test
    fun `activation persistence completes after caller cancellation while waiting for lifecycle lock`() = test {
        val account = account().copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val coordinator = WatchOnlyAccountLifecycleCoordinator()
        val lockAcquired = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = listOf(account))))
        whenever(store.load()).thenReturn(listOf(account))
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            coordinator,
        )
        val lockHolder = launch {
            coordinator.withLock {
                lockAcquired.complete(Unit)
                releaseLock.await()
            }
        }
        lockAcquired.await()

        val activation = launch { sut.markActive(account.id) }
        runCurrent()
        activation.cancel()
        runCurrent()

        verify(store, never()).markActive(any())
        releaseLock.complete(Unit)
        lockHolder.join()
        activation.join()

        verify(store).markActive(account.id)
    }

    @Test
    fun `retry with reordered query reuses the pending account and xpub without tracking it`() = test {
        var storedAccounts = emptyList<WatchOnlyAccountRecord>()
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.save(any())).thenAnswer {
            storedAccounts = it.getArgument(0)
            Unit
        }
        whenever(store.reserveAccountIndex(any(), any())).thenReturn(1)
        whenever(lightningService.node).thenReturn(node)
        whenever(node.exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 1u)).thenReturn(TEST_XPUB)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val first = sut.prepareUnsignedClaim(
            "pubkyauth://signin?relay=https%3A%2F%2Frelay.example&secret=same&" +
                "caps=%2Fpub%2Fpaykit%2Fv0%2Fbitkit%2Fserver%2F%3Arw&x-bitkit-claim=watch-only-account-v1",
            "Creator account",
        )
        val retry = sut.prepareUnsignedClaim(
            "pubkyauth://signin?x-bitkit-claim=watch-only-account-v1&" +
                "caps=%2Fpub%2Fpaykit%2Fv0%2Fbitkit%2Fserver%2F%3Arw&secret=same&" +
                "relay=https%3A%2F%2Frelay.example",
            "Renamed account",
        )

        assertEquals(first.account.id, retry.account.id)
        assertEquals(first.account.accountIndex, retry.account.accountIndex)
        assertEquals(first.account.xpub, retry.account.xpub)
        assertEquals("Renamed account", retry.account.name)
        assertFalse(retry.account.isTrackingEnabled)
        verify(node, times(1)).exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 1u)
        verify(node, never()).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, TEST_XPUB)
    }

    @Test
    fun `restored colliding request gets a fresh account index and xpub`() = test {
        val authorizingAccount = account().copy(
            accountIndex = 5,
            xpub = TEST_XPUB_ALTERNATE,
            requestFingerprint = "current-authorizing-request",
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val restoredRequestKey = "0:$RESTORED_REQUEST_FINGERPRINT"
        var storedData = WatchOnlyAccountData(
            accounts = listOf(authorizingAccount),
            highestAccountIndexByWallet = mapOf("0" to 5),
            pendingAccountIndexByRequest = mapOf(
                "0:${authorizingAccount.requestFingerprint}" to 5,
            ),
        ).restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 5),
                pendingAccountIndexByRequest = mapOf(restoredRequestKey to 5),
            ),
        )
        assertFalse(restoredRequestKey in storedData.pendingAccountIndexByRequest)
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        whenever(store.data).thenReturn(flowOf(storedData))
        whenever(store.load()).thenAnswer { storedData.accounts }
        whenever(store.reserveAccountIndex(any(), any())).thenAnswer {
            val reservation = storedData.reserveAccountIndex(
                walletIndex = it.getArgument(0),
                requestFingerprint = it.getArgument(1),
            )
            storedData = reservation.data
            reservation.accountIndex
        }
        whenever(store.save(any())).thenAnswer {
            storedData = storedData.copy(accounts = it.getArgument(0))
            Unit
        }
        whenever(lightningService.currentWalletIndex).thenReturn(0)
        whenever(lightningService.node).thenReturn(node)
        whenever(node.exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 6u)).thenReturn(TEST_XPUB)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val prepared = sut.prepareUnsignedClaim(RESTORED_AUTH_URL, "Restored server")

        assertEquals(6, prepared.account.accountIndex)
        assertEquals(TEST_XPUB, prepared.account.xpub)
        assertNotEquals(authorizingAccount.xpub, prepared.account.xpub)
        assertEquals(6, storedData.pendingAccountIndexByRequest[restoredRequestKey])
        assertEquals(listOf(5, 6), storedData.accounts.map(WatchOnlyAccountRecord::accountIndex))
        verify(node).exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 6u)
        verify(node, never()).exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 5u)
    }

    @Test
    fun `older pending reservation cannot reuse a later active account index`() = test {
        val activeAccount = account().copy(
            accountIndex = 5,
            xpub = TEST_XPUB_ALTERNATE,
            requestFingerprint = RESTORED_REQUEST_FINGERPRINT,
            setupState = WatchOnlyAccountSetupState.Active,
        )
        val restoredRequestKey = "0:$RESTORED_REQUEST_FINGERPRINT"
        var storedData = WatchOnlyAccountData(
            accounts = listOf(activeAccount),
            highestAccountIndexByWallet = mapOf("0" to 5),
            pendingAccountIndexByRequest = mapOf(restoredRequestKey to 5),
        ).restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 5),
                pendingAccountIndexByRequest = mapOf(restoredRequestKey to 5),
            ),
        )
        assertEquals(listOf(activeAccount), storedData.accountsPendingRemoval)
        assertFalse(restoredRequestKey in storedData.pendingAccountIndexByRequest)
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        whenever(store.data).thenReturn(flowOf(storedData))
        whenever(store.load()).thenAnswer { storedData.accounts }
        whenever(store.reserveAccountIndex(any(), any())).thenAnswer {
            val reservation = storedData.reserveAccountIndex(
                walletIndex = it.getArgument(0),
                requestFingerprint = it.getArgument(1),
            )
            storedData = reservation.data
            reservation.accountIndex
        }
        whenever(store.save(any())).thenAnswer {
            storedData = storedData.copy(accounts = it.getArgument(0))
            Unit
        }
        whenever(lightningService.currentWalletIndex).thenReturn(0)
        whenever(lightningService.node).thenReturn(node)
        whenever(node.exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 6u)).thenReturn(TEST_XPUB)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val prepared = sut.prepareUnsignedClaim(RESTORED_AUTH_URL, "Restored server")

        assertEquals(6, prepared.account.accountIndex)
        assertEquals(TEST_XPUB, prepared.account.xpub)
        assertNotEquals(activeAccount.xpub, prepared.account.xpub)
        assertEquals(6, storedData.pendingAccountIndexByRequest[restoredRequestKey])
        assertEquals(listOf(6), storedData.accounts.map(WatchOnlyAccountRecord::accountIndex))
        assertEquals(listOf(5), storedData.accountsPendingRemoval.map(WatchOnlyAccountRecord::accountIndex))
        verify(node).exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 6u)
        verify(node, never()).exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, 5u)
    }

    @Test
    fun `failed authorization unloads the pending account`() = test {
        val account = account().copy(isTrackingEnabled = false, setupState = WatchOnlyAccountSetupState.PendingDelivery)
        var storedAccounts = listOf(account)
        var isTracked = false
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.save(any())).thenAnswer {
            storedAccounts = it.getArgument(0)
            Unit
        }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.listOnchainWalletAccounts()).thenAnswer {
            if (isTracked) listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)) else emptyList()
        }
        doAnswer { isTracked = true }.whenever(
            node
        ).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        doAnswer { isTracked = false }.whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        sut.beginAuthorization(account.id)
        sut.cancelAuthorization(account.id)

        assertFalse(storedAccounts.single().isTrackingEnabled)
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            1u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
    }

    @Test
    fun `retry failure keeps a delivered account authorizing and tracked`() = test {
        val account = account().copy(
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        var storedAccounts = listOf(account)
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.update(any())).thenAnswer {
            val transform = it.getArgument<(List<WatchOnlyAccountRecord>) -> List<WatchOnlyAccountRecord>>(0)
            storedAccounts = transform(storedAccounts)
            Unit
        }
        whenever(store.save(any())).thenAnswer {
            storedAccounts = it.getArgument(0)
            Unit
        }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)),
        )
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val preserveAuthorizingState = sut.beginAuthorization(account.id)
        sut.cancelAuthorization(account.id, preserveAuthorizingState)

        assertTrue(preserveAuthorizingState)
        assertEquals(WatchOnlyAccountSetupState.Authorizing, storedAccounts.single().setupState)
        assertTrue(storedAccounts.single().isTrackingEnabled)
        verify(node, never()).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
    }

    @Test
    fun `failed authorization keeps persisted state when unloading fails`() = test {
        val account = account().copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        var storedAccounts = listOf(account)
        val unloadError = IllegalStateException("unload failed")
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)),
        )
        doThrow(unloadError).whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val error = runCatching { sut.cancelAuthorization(account.id) }.exceptionOrNull()

        assertSame(unloadError, error?.cause)
        assertEquals(account, storedAccounts.single())
        verify(store, never()).save(any())
    }

    @Test
    fun `failed authorization reloads the account when persistence fails`() = test {
        val account = account().copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        var storedAccounts = listOf(account)
        var isTracked = true
        val persistenceError = IllegalStateException("persistence failed")
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.save(any())).thenThrow(persistenceError)
        whenever(lightningService.node).thenReturn(node)
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.listOnchainWalletAccounts()).thenAnswer {
            if (isTracked) listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)) else emptyList()
        }
        doAnswer { isTracked = false }.whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer { isTracked = true }.whenever(node)
            .addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val error = runCatching { sut.cancelAuthorization(account.id) }.exceptionOrNull()

        assertEquals(persistenceError.message, error?.message)
        assertTrue(isTracked)
        assertEquals(account, storedAccounts.single())
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            1u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node).syncWallets()
    }

    @Test
    fun `already tracked account still pre-reveals addresses without syncing`() = test {
        val account = account().copy(isTrackingEnabled = false, setupState = WatchOnlyAccountSetupState.PendingDelivery)
        var storedAccounts = listOf(account)
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.update(any())).thenAnswer {
            val transform = it.getArgument<(List<WatchOnlyAccountRecord>) -> List<WatchOnlyAccountRecord>>(0)
            storedAccounts = transform(storedAccounts)
            Unit
        }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        sut.beginAuthorization(account.id)

        assertTrue(storedAccounts.single().isTrackingEnabled)
        assertEquals(WatchOnlyAccountSetupState.Authorizing, storedAccounts.single().setupState)
        verify(node, never()).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            1u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node, never()).syncWallets()
    }

    @Test
    fun `new account is removed when initial sync fails and original error is preserved`() = test {
        val account = account().copy(isTrackingEnabled = false, setupState = WatchOnlyAccountSetupState.PendingDelivery)
        var storedAccounts = listOf(account)
        var isTracked = false
        val syncError = IllegalStateException("initial sync failed")
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.listOnchainWalletAccounts()).thenAnswer {
            if (isTracked) listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)) else emptyList()
        }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer { isTracked = true }.whenever(node)
            .addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        doAnswer { isTracked = false }.whenever(node)
            .removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        whenever(node.syncWallets()).thenThrow(syncError)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        val error = runCatching { sut.beginAuthorization(account.id) }.exceptionOrNull()

        assertFalse(isTracked)
        assertFalse(storedAccounts.single().isTrackingEnabled)
        assertEquals(WatchOnlyAccountSetupState.PendingDelivery, storedAccounts.single().setupState)
        assertSame(syncError, error?.cause)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            1u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
    }

    @Test
    fun `disable and re-enable unloads and restores the same account`() = test {
        val account = account()
        var storedAccounts = listOf(account)
        var isTracked = true
        val store = mock<WatchOnlyAccountStore>()
        val lightningService = mock<LightningService>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        whenever(store.data).thenReturn(flowOf(WatchOnlyAccountData(accounts = storedAccounts)))
        whenever(store.load()).thenAnswer { storedAccounts }
        whenever(store.save(any())).thenAnswer {
            storedAccounts = it.getArgument(0)
            Unit
        }
        whenever(lightningService.node).thenReturn(node)
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.listOnchainWalletAccounts()).thenAnswer {
            if (isTracked) listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)) else emptyList()
        }
        doAnswer { isTracked = false }.whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer { isTracked = true }.whenever(
            node
        ).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        val sut = WatchOnlyAccountRepo(
            testDispatcher,
            store,
            lightningService,
            WatchOnlyAccountLifecycleCoordinator(),
        )

        sut.setTrackingEnabled(account.id, enabled = false)

        assertFalse(storedAccounts.single().isTrackingEnabled)
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)

        sut.setTrackingEnabled(account.id, enabled = true)

        assertTrue(storedAccounts.single().isTrackingEnabled)
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            1u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node, times(1)).syncWallets()
    }

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService, coordinator)

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService, coordinator)

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

    private fun account() = WatchOnlyAccountRecord(
        id = "account-id",
        walletIndex = 0,
        accountIndex = 1,
        addressType = WatchOnlyAccountRepo.ADDRESS_TYPE_NATIVE_SEGWIT,
        xpub = "xpub",
        requestFingerprint = "request",
        createdAt = 1,
        name = "Creator account",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Active,
    )

    private companion object {
        const val RESTORED_AUTH_URL =
            "pubkyauth://signin?relay=https%3A%2F%2Frelay.example&secret=backup&" +
                "caps=%2Fpub%2Fpaykit%2Fv0%2Fbitkit%2Fserver%2F%3Arw&x-bitkit-claim=watch-only-account-v1"
        const val RESTORED_REQUEST_FINGERPRINT = "vuq8iJuzlfl/Jp58+w7KMgk1BNhEA5PJumkZfUrZjoY="
        const val TEST_XPUB =
            "tpubDCgMbrEACV32r3jqiWn685NmYnqDkAcas1GBGh7XUVhxKFagQdpd2aY5kBMFqAFRa9NWPzCHma" +
                "BEsU7YJcyjX8M8sswT3e6wq4LKCep3YaP"
        const val TEST_XPUB_ALTERNATE =
            "tpubDCgMbrEACV32r3jqiWn685Ni6Z8vkPtVn73Pv5ZvyXkwh4iFbrpcrXXPvtJhiCvHvRvZY6dXU" +
                "gKt8aZEPpo4tRpHkNC7jR9B7JVZo8kFxCz"
    }
}
