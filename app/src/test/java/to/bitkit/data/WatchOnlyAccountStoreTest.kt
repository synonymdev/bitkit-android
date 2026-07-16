package to.bitkit.data

import org.junit.Test
import to.bitkit.di.json
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchOnlyAccountStoreTest {
    private companion object {
        const val TEST_XPUB =
            "tpubDCgMbrEACV32r3jqiWn685NmYnqDkAcas1GBGh7XUVhxKFagQdpd2aY5kBMFqAFRa9NWPzCHma" +
                "BEsU7YJcyjX8M8sswT3e6wq4LKCep3YaP"
    }

    @Test
    fun `allocation is monotonic and retries reuse their reservation`() {
        var data = WatchOnlyAccountData()

        fun reserve(requestFingerprint: String): Int {
            val reservation = data.reserveAccountIndex(walletIndex = 0, requestFingerprint)
            data = reservation.data
            return reservation.accountIndex
        }

        assertEquals(1, reserve("first"))
        assertEquals(1, reserve("first"))
        assertEquals(2, reserve("second"))
        assertEquals(2, data.highestAccountIndexByWallet["0"])
    }

    @Test
    fun `persisted accounts restore the allocator high water mark`() {
        val data = WatchOnlyAccountData(accounts = listOf(account(accountIndex = 7)))
        val reservation = data.reserveAccountIndex(walletIndex = 0, requestFingerprint = "next")

        assertEquals(8, reservation.accountIndex)
        assertEquals(7, reservation.data.accounts.single().accountIndex)
    }

    @Test
    fun `restoring an older backup preserves high water and clears unstored reservations`() {
        val data = WatchOnlyAccountData(
            highestAccountIndexByWallet = mapOf("0" to 10),
            pendingAccountIndexByRequest = mapOf("0:pending" to 10),
        )

        val restored = data.restoreTestAccounts(listOf(account(accountIndex = 7)))

        assertEquals(emptyMap(), restored.pendingAccountIndexByRequest)
        assertEquals(11, restored.reserveAccountIndex(walletIndex = 0, requestFingerprint = "pending").accountIndex)
    }

    @Test
    fun `allocator backup restores pending reuse and monotonic high water`() {
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 9),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )

        val restored = WatchOnlyAccountData().restoreTestAccounts(
            accounts = listOf(account(accountIndex = 5)),
            allocationState = allocationState,
        )

        assertEquals(7, restored.reserveAccountIndex(0, "pending").accountIndex)
        assertEquals(10, restored.reserveAccountIndex(0, "new").accountIndex)
    }

    @Test
    fun `restore sanitizes accounts and normalizes incomplete tracking`() {
        val pending = account(accountIndex = 1).copy(
            requestFingerprint = "pending",
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val authorizing = account(accountIndex = 2).copy(
            requestFingerprint = "authorizing",
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val activeDisabled = account(accountIndex = 3).copy(isTrackingEnabled = false)
        val duplicateSlot = account(accountIndex = 1).copy(
            id = "duplicate",
            requestFingerprint = "duplicate",
        )
        val invalid = account(accountIndex = 4).copy(xpub = "invalid")

        val restored = WatchOnlyAccountData().restoreTestAccounts(
            listOf(pending, authorizing, activeDisabled, duplicateSlot, invalid),
        )

        assertEquals(listOf(pending.id, authorizing.id, activeDisabled.id), restored.accounts.map { it.id })
        assertEquals(listOf(false, true, false), restored.accounts.map { it.isTrackingEnabled })
    }

    @Test
    fun `restore drops unusable accounts and burns their indexes`() {
        val valid = account(accountIndex = 1)
        val invalidAddressType = account(accountIndex = 7).copy(addressType = "legacy")
        val invalidXpub = account(accountIndex = 8).copy(xpub = "not-an-xpub")
        val accountZero = account(accountIndex = 0)

        val restored = WatchOnlyAccountData().restoreTestAccounts(
            listOf(invalidXpub, accountZero, invalidAddressType, valid),
        )

        assertEquals(listOf(valid), restored.accounts)
        assertEquals(9, restored.reserveAccountIndex(0, "next").accountIndex)
    }

    @Test
    fun `restore persists replaced accounts until runtime reconciliation completes`() {
        val replacedAccount = account(accountIndex = 1)
        val restoredAccount = account(accountIndex = 2)

        val restored = WatchOnlyAccountData(accounts = listOf(replacedAccount))
            .restoreTestAccounts(accounts = listOf(restoredAccount))
        val reloaded = json.decodeFromString<WatchOnlyAccountData>(json.encodeToString(restored))
        val otherWalletPendingRemoval = account(accountIndex = 3, walletIndex = 1)

        assertEquals(listOf(restoredAccount), reloaded.accounts)
        assertEquals(listOf(replacedAccount), reloaded.accountsPendingRemoval)
        assertEquals(
            listOf(otherWalletPendingRemoval),
            reloaded.copy(accountsPendingRemoval = reloaded.accountsPendingRemoval + otherWalletPendingRemoval)
                .completeReconciliation(walletIndex = 0)
                .accountsPendingRemoval,
        )
    }

    @Test
    fun `wallet backup round trip retains allocator state`() {
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 9),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )
        val payload = WalletBackupV1(
            createdAt = 1,
            transfers = emptyList(),
            watchOnlyAccounts = listOf(account(accountIndex = 5)),
            watchOnlyAccountAllocationState = allocationState,
        )

        val restored = json.decodeFromString<WalletBackupV1>(json.encodeToString(payload))

        assertEquals(allocationState, restored.watchOnlyAccountAllocationState)
        assertEquals(5, restored.watchOnlyAccounts?.single()?.accountIndex)
    }

    @Test
    fun `activation updates account and completes reservation in one snapshot`() {
        val pendingAccount = account(accountIndex = 7).copy(
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val requestKey = "0:${pendingAccount.requestFingerprint}"
        val data = WatchOnlyAccountData(
            accounts = listOf(pendingAccount),
            highestAccountIndexByWallet = mapOf("0" to 7),
            pendingAccountIndexByRequest = mapOf(requestKey to 7, "0:other" to 8),
        )

        val activated = data.markAccountActive(pendingAccount.id)

        assertTrue(activated.accounts.single().isTrackingEnabled)
        assertEquals(WatchOnlyAccountSetupState.Active, activated.accounts.single().setupState)
        assertFalse(requestKey in activated.pendingAccountIndexByRequest)
        assertEquals(8, activated.pendingAccountIndexByRequest["0:other"])
    }

    @Test
    fun `restore preserves an authorizing account over backup state`() {
        val authorizing = account(accountIndex = 4).copy(
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val requestKey = "0:${authorizing.requestFingerprint}"
        val restoredActive = authorizing.copy(setupState = WatchOnlyAccountSetupState.Active)
        val restored = WatchOnlyAccountData(
            accounts = listOf(authorizing),
            pendingAccountIndexByRequest = mapOf(requestKey to authorizing.accountIndex),
        ).restoreTestAccounts(listOf(restoredActive))

        assertEquals(listOf(authorizing), restored.accounts)
        assertEquals(authorizing.accountIndex, restored.pendingAccountIndexByRequest[requestKey])
    }

    @Test
    fun `restore preserves a local owner when backup reuses its slot`() {
        val local = account(accountIndex = 5)
        val conflictingBackup = local.copy(
            id = "restored-owner",
            requestFingerprint = "restored-request",
        )

        val restored = WatchOnlyAccountData(accounts = listOf(local)).restoreTestAccounts(listOf(conflictingBackup))

        assertEquals(listOf(local), restored.accounts)
        assertTrue(restored.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `activation fails when the account is missing`() {
        val error = assertFailsWith<IllegalStateException> {
            WatchOnlyAccountData().markAccountActive("missing")
        }

        assertEquals("Watch-only account 'missing' not found", error.message)
    }

    private fun account(accountIndex: Int, walletIndex: Int = 0) = WatchOnlyAccountRecord(
        id = "account-$walletIndex-$accountIndex",
        walletIndex = walletIndex,
        accountIndex = accountIndex,
        addressType = WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE,
        xpub = TEST_XPUB,
        requestFingerprint = "request-$walletIndex-$accountIndex",
        createdAt = 1,
        name = "Account $accountIndex",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Active,
    )

    private fun WatchOnlyAccountData.restoreTestAccounts(
        accounts: List<WatchOnlyAccountRecord>,
        allocationState: WatchOnlyAccountAllocationState? = null,
    ) = restoreAccounts(accounts, allocationState) { xpub ->
        require(xpub == TEST_XPUB)
        ByteArray(78)
    }
}
