package to.bitkit.repositories

import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.AddressType
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.OnchainPayment
import org.lightningdevkit.ldknode.OnchainWalletAccount
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WatchOnlyAccountRepoTest : BaseUnitTest() {
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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService)

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService)

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService)

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService)

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
        val sut = WatchOnlyAccountRepo(testDispatcher, store, lightningService)

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
        const val TEST_XPUB =
            "tpubDCgMbrEACV32r3jqiWn685NmYnqDkAcas1GBGh7XUVhxKFagQdpd2aY5kBMFqAFRa9NWPzCHma" +
                "BEsU7YJcyjX8M8sswT3e6wq4LKCep3YaP"
    }
}
