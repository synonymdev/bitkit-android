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

        data = data.completeAccountIndexAllocation("0:first")

        assertEquals(3, reserve("first"))
        assertEquals(3, data.highestAccountIndexByWallet["0"])
    }

    @Test
    fun `persisted accounts restore the allocator high water mark`() {
        val data = WatchOnlyAccountData(accounts = listOf(account(accountIndex = 7)))
        val reservation = data.reserveAccountIndex(walletIndex = 0, requestFingerprint = "next")

        assertEquals(8, reservation.accountIndex)
        assertEquals(7, reservation.data.accounts.single().accountIndex)
    }

    @Test
    fun `restoring an older backup does not lower the allocator high water mark`() {
        val data = WatchOnlyAccountData(
            highestAccountIndexByWallet = mapOf("0" to 10),
            pendingAccountIndexByRequest = mapOf("0:pending" to 10),
        )

        val restored = data.restoreAccounts(listOf(account(accountIndex = 7)))
        val reservation = restored.reserveAccountIndex(walletIndex = 0, requestFingerprint = "next")

        assertEquals(emptyMap(), restored.pendingAccountIndexByRequest)
        assertEquals(11, reservation.accountIndex)
    }

    @Test
    fun `restoring allocator backup preserves pending reuse and monotonic high water mark`() {
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 9),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )

        val restored = WatchOnlyAccountData().restoreAccounts(
            accounts = listOf(account(accountIndex = 5)),
            allocationState = allocationState,
        )

        assertEquals(7, restored.reserveAccountIndex(0, "pending").accountIndex)
        assertEquals(10, restored.reserveAccountIndex(0, "new").accountIndex)
        assertEquals(allocationState.pendingAccountIndexByRequest, restored.pendingAccountIndexByRequest)
    }

    @Test
    fun `restored pending reservation raises an inconsistent allocator high water mark`() {
        val restored = WatchOnlyAccountData().restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 2),
                pendingAccountIndexByRequest = mapOf("0:pending" to 9),
            ),
        )

        assertEquals(9, restored.highestAccountIndexByWallet["0"])
        assertEquals(9, restored.reserveAccountIndex(0, "pending").accountIndex)
        assertEquals(10, restored.reserveAccountIndex(0, "new").accountIndex)
    }

    @Test
    fun `restore drops an unbound reservation below the local high water mark`() {
        val restored = WatchOnlyAccountData(
            highestAccountIndexByWallet = mapOf("0" to 9),
        ).restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 7),
                pendingAccountIndexByRequest = mapOf("0:stale" to 7),
            ),
        )

        assertFalse("0:stale" in restored.pendingAccountIndexByRequest)
        assertEquals(9, restored.highestAccountIndexByWallet["0"])
        assertEquals(10, restored.reserveAccountIndex(0, "stale").accountIndex)
    }

    @Test
    fun `restore keeps only an exact local pending reservation below the high water mark`() {
        val local = WatchOnlyAccountData(
            highestAccountIndexByWallet = mapOf("0" to 7),
            pendingAccountIndexByRequest = mapOf("0:pending" to 7),
        )
        val exact = local.restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 7),
                pendingAccountIndexByRequest = mapOf("0:pending" to 7),
            ),
        )
        val divergent = local.restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 0),
                pendingAccountIndexByRequest = mapOf("0:pending" to 8),
            ),
        )

        assertEquals(7, exact.pendingAccountIndexByRequest["0:pending"])
        assertEquals(7, exact.reserveAccountIndex(0, "pending").accountIndex)
        assertEquals(8, exact.reserveAccountIndex(0, "next").accountIndex)
        assertFalse("0:pending" in divergent.pendingAccountIndexByRequest)
        assertEquals(9, divergent.reserveAccountIndex(0, "pending").accountIndex)
    }

    @Test
    fun `restore deterministically keeps one request per free account index`() {
        val restored = WatchOnlyAccountData().restoreAccounts(
            accounts = emptyList(),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 2),
                pendingAccountIndexByRequest = linkedMapOf(
                    "0:z-request" to 7,
                    "0:a-request" to 7,
                ),
            ),
        )

        assertEquals(mapOf("0:a-request" to 7), restored.pendingAccountIndexByRequest)
        assertEquals(7, restored.highestAccountIndexByWallet["0"])
        assertEquals(7, restored.reserveAccountIndex(0, "a-request").accountIndex)
        assertEquals(8, restored.reserveAccountIndex(0, "z-request").accountIndex)
    }

    @Test
    fun `restore keeps one deterministic owner for a duplicate id`() {
        val completed = account(accountIndex = 7).copy(id = "duplicate-id", createdAt = 2)
        val incomplete = account(accountIndex = 3).copy(
            id = completed.id,
            createdAt = 1,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )

        val forward = WatchOnlyAccountData().restoreAccounts(
            accounts = listOf(incomplete, completed),
            allocationState = WatchOnlyAccountAllocationState(
                pendingAccountIndexByRequest = mapOf(
                    "0:${incomplete.requestFingerprint}" to incomplete.accountIndex,
                ),
            ),
        )
        val reversed = WatchOnlyAccountData().restoreAccounts(listOf(completed, incomplete))

        assertEquals(listOf(completed), forward.accounts)
        assertEquals(forward.accounts, reversed.accounts)
        assertEquals(7, forward.highestAccountIndexByWallet["0"])
        assertFalse("0:${incomplete.requestFingerprint}" in forward.pendingAccountIndexByRequest)
    }

    @Test
    fun `restore rejects a reservation at a discarded account index`() {
        val retained = account(accountIndex = 1).copy(id = "duplicate-id")
        val discarded = account(accountIndex = 7).copy(id = retained.id)
        val restored = WatchOnlyAccountData().restoreAccounts(
            accounts = listOf(discarded, retained),
            allocationState = WatchOnlyAccountAllocationState(
                highestAccountIndexByWallet = mapOf("0" to 7),
                pendingAccountIndexByRequest = mapOf("0:retry" to 7),
            ),
        )

        assertFalse("0:retry" in restored.pendingAccountIndexByRequest)
        assertEquals(8, restored.reserveAccountIndex(0, "retry").accountIndex)
    }

    @Test
    fun `restore normalizes tracking for incomplete accounts`() {
        val pending = account(accountIndex = 1).copy(
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val authorizing = account(accountIndex = 2).copy(
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val disabledActive = account(accountIndex = 3).copy(isTrackingEnabled = false)

        val restored = WatchOnlyAccountData().restoreAccounts(listOf(pending, authorizing, disabledActive))

        assertFalse(restored.accounts.first { it.id == pending.id }.isTrackingEnabled)
        assertTrue(restored.accounts.first { it.id == authorizing.id }.isTrackingEnabled)
        assertFalse(restored.accounts.first { it.id == disabledActive.id }.isTrackingEnabled)
    }

    @Test
    fun `restore drops unusable accounts and burns their indexes`() {
        val valid = account(accountIndex = 1)
        val invalidAddressType = account(accountIndex = 7).copy(addressType = "legacy")
        val invalidXpub = account(accountIndex = 8).copy(
            xpub = "not-an-xpub",
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val accountZero = account(accountIndex = 0)

        val restored = WatchOnlyAccountData().restoreAccounts(
            listOf(invalidXpub, accountZero, invalidAddressType, valid),
        )

        assertEquals(listOf(valid), restored.accounts)
        assertEquals(9, restored.reserveAccountIndex(0, "next").accountIndex)
    }

    @Test
    fun `restore keeps one deterministic owner for a duplicate management key`() {
        val completed = account(accountIndex = 5).copy(id = "completed", createdAt = 2)
        val incomplete = completed.copy(
            id = "incomplete",
            xpub = "other-xpub",
            requestFingerprint = "other-request",
            createdAt = 1,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )

        val forward = WatchOnlyAccountData().restoreAccounts(listOf(incomplete, completed))
        val reversed = WatchOnlyAccountData().restoreAccounts(listOf(completed, incomplete))

        assertEquals(listOf(completed), forward.accounts)
        assertEquals(forward.accounts, reversed.accounts)
    }

    @Test
    fun `restore keeps one deterministic owner for an incomplete request`() {
        val first = account(accountIndex = 4).copy(
            id = "first",
            requestFingerprint = "shared-request",
            createdAt = 1,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val later = account(accountIndex = 3).copy(
            id = "later",
            requestFingerprint = first.requestFingerprint,
            createdAt = 2,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val allocationState = WatchOnlyAccountAllocationState(
            highestAccountIndexByWallet = mapOf("0" to 4),
            pendingAccountIndexByRequest = mapOf("0:${first.requestFingerprint}" to first.accountIndex),
        )

        val forward = WatchOnlyAccountData().restoreAccounts(listOf(later, first), allocationState)
        val reversed = WatchOnlyAccountData().restoreAccounts(listOf(first, later), allocationState)

        assertEquals(listOf(first), forward.accounts)
        assertEquals(forward.accounts, reversed.accounts)
        assertEquals(first.accountIndex, forward.pendingAccountIndexByRequest["0:${first.requestFingerprint}"])
        assertEquals(4, forward.highestAccountIndexByWallet["0"])
    }

    @Test
    fun `restore persists replaced accounts until runtime reconciliation completes`() {
        val replacedAccount = account(accountIndex = 1)
        val restoredAccount = account(accountIndex = 2)

        val restored = WatchOnlyAccountData(accounts = listOf(replacedAccount))
            .restoreAccounts(accounts = listOf(restoredAccount))
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
        assertEquals(7, activated.highestAccountIndexByWallet["0"])
        assertTrue(requestKey in data.pendingAccountIndexByRequest)
    }

    @Test
    fun `restore preserves and activates authorizing accounts over backup conflicts`() {
        val authorizingById = account(accountIndex = 4).copy(
            id = "authorizing-by-id",
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val authorizingByIdentity = account(accountIndex = 5).copy(
            id = "authorizing-by-identity",
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val conflictById = account(accountIndex = 6).copy(id = authorizingById.id)
        val conflictByIdentity = authorizingByIdentity.copy(
            id = "restored-conflict",
            requestFingerprint = "restored-conflict",
            setupState = WatchOnlyAccountSetupState.Active,
        )
        val restoredAccount = account(accountIndex = 7)
        val restoredPendingKey = "0:${restoredAccount.requestFingerprint}"
        val data = WatchOnlyAccountData(
            accounts = listOf(authorizingById, authorizingByIdentity),
            pendingAccountIndexByRequest = mapOf(
                "0:${authorizingById.requestFingerprint}" to authorizingById.accountIndex,
                "0:${authorizingByIdentity.requestFingerprint}" to authorizingByIdentity.accountIndex,
            ),
        )

        val restored = data.restoreAccounts(
            accounts = listOf(conflictById, conflictByIdentity, restoredAccount),
            allocationState = WatchOnlyAccountAllocationState(
                pendingAccountIndexByRequest = mapOf(restoredPendingKey to restoredAccount.accountIndex),
            ),
        )
        val activated = restored.markAccountActive(authorizingById.id)

        assertEquals(
            setOf(authorizingById.id, authorizingByIdentity.id, restoredAccount.id),
            restored.accounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::id),
        )
        assertEquals(
            WatchOnlyAccountSetupState.Active,
            activated.accounts.first { it.id == authorizingById.id }.setupState,
        )
        assertTrue(activated.accounts.first { it.id == authorizingById.id }.isTrackingEnabled)
        assertEquals(authorizingByIdentity, restored.accounts.first { it.id == authorizingByIdentity.id })
        assertEquals(
            authorizingById.accountIndex,
            restored.pendingAccountIndexByRequest["0:${authorizingById.requestFingerprint}"],
        )
        assertEquals(
            authorizingByIdentity.accountIndex,
            restored.pendingAccountIndexByRequest["0:${authorizingByIdentity.requestFingerprint}"],
        )
        assertFalse(restoredPendingKey in restored.pendingAccountIndexByRequest)
        assertTrue(restored.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `restore preserves a local owner when backup reuses its slot`() {
        val local = account(accountIndex = 5)
        val conflictingBackup = local.copy(
            id = "restored-owner",
            requestFingerprint = "restored-request",
        )

        val restored = WatchOnlyAccountData(accounts = listOf(local)).restoreAccounts(listOf(conflictingBackup))

        assertEquals(listOf(local), restored.accounts)
        assertTrue(restored.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `restore prefers a delivered owner over a local pending slot`() {
        val localPending = account(accountIndex = 5).copy(
            requestFingerprint = "local-pending",
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val restoredActive = account(accountIndex = 5).copy(
            id = "restored-active",
            requestFingerprint = "restored-active",
        )

        val restored = WatchOnlyAccountData(accounts = listOf(localPending)).restoreAccounts(listOf(restoredActive))

        assertEquals(listOf(restoredActive), restored.accounts)
        assertTrue(restored.accounts.single().isTrackingEnabled)
        assertTrue(restored.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `restore keeps tracking enabled across delivered owner conflicts`() {
        val localDisabled = account(accountIndex = 5).copy(
            requestFingerprint = "local-active",
            isTrackingEnabled = false,
        )
        val restoredEnabled = account(accountIndex = 5).copy(
            id = "restored-active",
            requestFingerprint = "restored-active",
        )

        val restored = WatchOnlyAccountData(accounts = listOf(localDisabled)).restoreAccounts(listOf(restoredEnabled))

        assertEquals(localDisabled.id, restored.accounts.single().id)
        assertTrue(restored.accounts.single().isTrackingEnabled)
    }

    @Test
    fun `restore protects an owner awaiting removal from a conflicting slot`() {
        val local = account(accountIndex = 5)
        val conflictingBackup = local.copy(
            id = "restored-owner",
            requestFingerprint = "restored-request",
        )
        val awaitingRemoval = WatchOnlyAccountData(accounts = listOf(local)).restoreAccounts(emptyList())

        val restored = awaitingRemoval.restoreAccounts(listOf(conflictingBackup))

        assertEquals(listOf(local), restored.accounts)
        assertTrue(restored.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `restore does not regress an active local owner to incomplete`() {
        val local = account(accountIndex = 5)
        val incompleteBackup = local.copy(
            id = "incomplete-backup",
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )

        val restored = WatchOnlyAccountData(accounts = listOf(local)).restoreAccounts(listOf(incompleteBackup))

        assertEquals(listOf(local), restored.accounts)
    }

    @Test
    fun `restore uses the same canonical incomplete owner regardless of input order`() {
        val lowerIndex = account(accountIndex = 3).copy(
            id = "z-lower-index",
            requestFingerprint = "shared-request",
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val higherIndex = account(accountIndex = 4).copy(
            id = "a-higher-index",
            requestFingerprint = lowerIndex.requestFingerprint,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )

        val forward = WatchOnlyAccountData().restoreAccounts(listOf(higherIndex, lowerIndex))
        val reversed = WatchOnlyAccountData().restoreAccounts(listOf(lowerIndex, higherIndex))

        assertEquals(listOf(lowerIndex), forward.accounts)
        assertEquals(forward.accounts, reversed.accounts)
    }

    @Test
    fun `current authorizing account wins a duplicate incomplete request`() {
        val authorizing = account(accountIndex = 8).copy(
            id = "authorizing",
            requestFingerprint = "shared-request",
            createdAt = 2,
            isTrackingEnabled = true,
            setupState = WatchOnlyAccountSetupState.Authorizing,
        )
        val restoredDuplicate = account(accountIndex = 1).copy(
            id = "restored",
            requestFingerprint = authorizing.requestFingerprint,
            createdAt = 1,
            isTrackingEnabled = false,
            setupState = WatchOnlyAccountSetupState.PendingDelivery,
        )
        val requestKey = "0:${authorizing.requestFingerprint}"
        val restored = WatchOnlyAccountData(
            accounts = listOf(authorizing),
            pendingAccountIndexByRequest = mapOf(requestKey to authorizing.accountIndex),
        ).restoreAccounts(
            accounts = listOf(restoredDuplicate),
            allocationState = WatchOnlyAccountAllocationState(
                pendingAccountIndexByRequest = mapOf(requestKey to restoredDuplicate.accountIndex),
            ),
        )

        assertEquals(listOf(authorizing), restored.accounts)
        assertEquals(authorizing.accountIndex, restored.pendingAccountIndexByRequest[requestKey])
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
}
