package to.bitkit.repositories

import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.AddressType
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.OnchainPayment
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountReconciliationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.restoreAccounts
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.services.WatchOnlyAccountLifecycleCoordinator
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import kotlin.test.assertEquals

class WatchOnlyAccountRestoreTest : BaseUnitTest() {
    @Test
    fun `retry and reconciliation use one canonical incomplete account after restore`() = test {
        val canonicalAccount = account(id = "canonical-account", accountIndex = 5, createdAt = 1)
        val duplicateAccount = account(id = "duplicate-account", accountIndex = 6, createdAt = 2)
        val requestKey = "0:$REQUEST_FINGERPRINT"
        val storedData = WatchOnlyAccountData().restoreAccounts(
            accounts = listOf(duplicateAccount, canonicalAccount),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 6),
                pendingAccountIndexByRequest = mapOf(requestKey to canonicalAccount.accountIndex),
            ),
        )
        val store = mock<WatchOnlyAccountStore>()
        val node = mock<Node>()
        val onchainPayment = mock<OnchainPayment>()
        val coordinator = WatchOnlyAccountLifecycleCoordinator()
        whenever(store.data).thenReturn(flowOf(storedData))
        whenever(store.load()).thenReturn(storedData.accounts)
        whenever(store.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(storedData.accounts, storedData.accountsPendingRemoval),
        )
        whenever(node.listOnchainWalletAccounts()).thenReturn(emptyList())
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        val lightningService = lightningService(store, node, coordinator)
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService, coordinator)

        val prepared = sut.prepareUnsignedClaim(AUTH_URL, canonicalAccount.name)
        lightningService.reconcileWatchOnlyAccounts(syncAfterReconcile = false)

        assertEquals(listOf(canonicalAccount), storedData.accounts)
        assertEquals(canonicalAccount.id, prepared.account.id)
        assertEquals(canonicalAccount.accountIndex, prepared.account.accountIndex)
        verify(store, never()).reserveAccountIndex(any(), any())
        verify(node, never()).exportOnchainWalletAccountXpub(any(), any())
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u, TEST_XPUB)
        verify(node, never()).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 6u, TEST_XPUB)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            5u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(onchainPayment, never()).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            6u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
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

    private fun account(id: String, accountIndex: Int, createdAt: Long) = WatchOnlyAccountRecord(
        id = id,
        walletIndex = 0,
        accountIndex = accountIndex,
        addressType = WatchOnlyAccountRepo.ADDRESS_TYPE_NATIVE_SEGWIT,
        xpub = TEST_XPUB,
        requestFingerprint = REQUEST_FINGERPRINT,
        createdAt = createdAt,
        name = "Restored server",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Authorizing,
    )

    private companion object {
        const val AUTH_URL =
            "pubkyauth://signin?relay=https%3A%2F%2Frelay.example&secret=backup&" +
                "caps=%2Fpub%2Fpaykit%2Fv0%2Fbitkit%2Fserver%2F%3Arw&x-bitkit-claim=watch-only-account-v1"
        const val REQUEST_FINGERPRINT = "vuq8iJuzlfl/Jp58+w7KMgk1BNhEA5PJumkZfUrZjoY="
        const val TEST_XPUB =
            "tpubDCgMbrEACV32r3jqiWn685NmYnqDkAcas1GBGh7XUVhxKFagQdpd2aY5kBMFqAFRa9NWPzCHma" +
                "BEsU7YJcyjX8M8sswT3e6wq4LKCep3YaP"
    }
}
