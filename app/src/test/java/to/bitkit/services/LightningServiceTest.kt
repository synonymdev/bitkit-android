package to.bitkit.services

import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.AddressType
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.OnchainPayment
import org.lightningdevkit.ldknode.OnchainWalletAccount
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountReconciliationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightningServiceTest : BaseUnitTest() {
    private val keychain = mock<Keychain>()
    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val settingsStore = mock<SettingsStore>()
    private val watchOnlyAccountStore = mock<WatchOnlyAccountStore>()
    private val loggerLdk = mock<LoggerLdk>()
    private val node = mock<Node>()
    private val watchOnlyAccountLifecycleCoordinator = WatchOnlyAccountLifecycleCoordinator()

    private lateinit var sut: LightningService

    @Before
    fun setUp() {
        sut = LightningService(
            bgDispatcher = testDispatcher,
            keychain = keychain,
            vssStoreIdProvider = vssStoreIdProvider,
            settingsStore = settingsStore,
            watchOnlyAccountStore = watchOnlyAccountStore,
            loggerLdk = loggerLdk,
            watchOnlyAccountLifecycleCoordinator = watchOnlyAccountLifecycleCoordinator,
        )
        sut.node = node
    }

    @Test
    fun `canReceive returns false when channel is ready but not usable`() {
        val readyButNotUsable = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = false,
        )
        whenever(node.listChannels()).thenReturn(listOf(readyButNotUsable))

        assertFalse(sut.canReceive())
    }

    @Test
    fun `canReceive returns true when channel is usable`() {
        val usableChannel = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = true,
        )
        whenever(node.listChannels()).thenReturn(listOf(usableChannel))

        assertTrue(sut.canReceive())
    }

    @Test
    fun `startup config includes enabled active and authorizing accounts for current wallet`() {
        val enabled = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val disabled = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = false)
        val otherWallet = watchOnlyAccount(accountIndex = 3, walletIndex = 1, enabled = true)
        val pending = watchOnlyAccount(accountIndex = 4, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.PendingDelivery)
        val authorizing = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)

        val configs = enabledOnchainWalletAccountConfigs(
            records = listOf(enabled, disabled, otherWallet, pending, authorizing),
            walletIndex = 0,
        )

        assertEquals(listOf(1u, 5u), configs.map { it.accountIndex })
        assertEquals(listOf(enabled.xpub, authorizing.xpub), configs.map { it.xpub })
    }

    @Test
    fun `reconciliation pre-reveals index 999 for an already tracked authorizing account`() = test {
        val account = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        val onchainPayment = mock<OnchainPayment>()
        val status = mock<NodeStatus>()
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u))
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.status()).thenReturn(status)
        whenever(status.isRunning).thenReturn(true)

        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(listOf(account), emptyList()),
        )

        sut.reconcileWatchOnlyAccounts()

        verify(node, never()).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            5u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node).syncWallets()
    }

    @Test
    fun `wallet sync retries watch-only reconciliation and syncs once`() = test {
        val account = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(listOf(account), emptyList()),
        )
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u))
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)

        sut.sync()

        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            5u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node, times(1)).syncWallets()
    }

    @Test
    fun `restore reconciliation unloads replaced account and loads restored account`() = test {
        val replacedAccount = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val restoredAccount = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = true)
        val trackedAccounts = mutableListOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(
                accounts = listOf(restoredAccount),
                accountsPendingRemoval = listOf(replacedAccount),
            ),
        )
        whenever(node.listOnchainWalletAccounts()).thenAnswer { trackedAccounts.toList() }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer {
            trackedAccounts.remove(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
            Unit
        }
            .whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer { trackedAccounts += OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u) }
            .whenever(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)

        sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false)

        assertEquals(listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)), trackedAccounts)
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)
        verify(watchOnlyAccountStore).completeReconciliation(walletIndex = 0)
    }

    @Test
    fun `failed restore reconciliation retains removals for the next retry`() = test {
        val replacedAccount = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val restoredAccount = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = true)
        val trackedAccounts = mutableListOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
        var failAdd = true
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(
                accounts = listOf(restoredAccount),
                accountsPendingRemoval = listOf(replacedAccount),
            ),
        )
        whenever(node.listOnchainWalletAccounts()).thenAnswer { trackedAccounts.toList() }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer {
            trackedAccounts.remove(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
            Unit
        }
            .whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer {
            check(!failAdd) { "add failed" }
            trackedAccounts += OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)
        }.whenever(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)

        assertTrue(runCatching { sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false) }.isFailure)
        verify(watchOnlyAccountStore, never()).completeReconciliation(walletIndex = 0)

        failAdd = false
        sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false)

        assertEquals(listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)), trackedAccounts)
        verify(watchOnlyAccountStore).completeReconciliation(walletIndex = 0)
    }

    private fun watchOnlyAccount(accountIndex: Int, walletIndex: Int, enabled: Boolean) = WatchOnlyAccountRecord(
        id = "account-$accountIndex",
        walletIndex = walletIndex,
        accountIndex = accountIndex,
        addressType = "nativeSegwit",
        xpub = "xpub-$accountIndex",
        requestFingerprint = "request-$accountIndex",
        createdAt = 1,
        name = "Account $accountIndex",
        isTrackingEnabled = enabled,
        setupState = WatchOnlyAccountSetupState.Active,
    )
}
