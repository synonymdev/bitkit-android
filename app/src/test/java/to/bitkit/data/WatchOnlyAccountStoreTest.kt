package to.bitkit.data

import org.junit.Test
import to.bitkit.di.json
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchOnlyAccountStoreTest {
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

    private fun account(accountIndex: Int) = WatchOnlyAccountRecord(
        id = "account-$accountIndex",
        walletIndex = 0,
        accountIndex = accountIndex,
        addressType = "nativeSegwit",
        xpub = "xpub",
        requestFingerprint = "request-$accountIndex",
        createdAt = 1,
        name = "Account $accountIndex",
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.Active,
    )
}
