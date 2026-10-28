package to.bitkit.repositories

import androidx.datastore.core.DataStore
import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.UsdtWallet
import com.synonym.bitkitcore.usdtParseAmount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.InMemoryDataStore
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UsdtRepoTest : BaseUnitTest() {
    private val service: UsdtService = mock()
    private val wallet: UsdtWallet = mock()
    private val transfer: UsdtTransfer = mock()

    private fun quote(expiresAt: ULong = ULong.MAX_VALUE): UsdtQuote = mock {
        on { this.expiresAt }.thenReturn(expiresAt)
    }

    @Test
    fun `display cache survives restart and offline refresh but never becomes a spendable balance`() = test {
        val cache = CacheStore(InMemoryDataStore(AppCacheData()))
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("wallet-a")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        whenever(wallet.refreshTransfers()).thenReturn(emptyList())
        whenever(wallet.balance()).thenReturn(1_234_567uL)
        assertTrue(UsdtRepo(service, cache, testDispatcher).refresh(history = false, force = true).isSuccess)
        whenever(wallet.balance()).thenThrow(UsdtException.NetworkUnavailable())
        val restarted = UsdtRepo(service, cache, testDispatcher)
        assertTrue(restarted.refresh(history = false, force = true).isFailure)
        assertEquals(1_234_567uL, restarted.state.value.cachedBalance)
        assertNull(restarted.state.value.balance)
        assertTrue(restarted.state.value.balanceIsStale)
        assertFalse(restarted.state.value.refreshing)
        doReturn(0uL).whenever(wallet).balance()
        assertTrue(restarted.refresh(history = false, force = true).isSuccess)
        assertEquals(0uL, restarted.state.value.balance)
        assertFalse(restarted.state.value.balanceIsStale)
        assertEquals(0uL, cache.data.first().usdtDisplayBalance?.balance)
        whenever(wallet.receiveAddress()).thenReturn("wallet-b")
        whenever(wallet.balance()).thenThrow(UsdtException.NetworkUnavailable())
        assertTrue(restarted.refresh(history = false, force = true).isFailure)
        assertNull(restarted.state.value.cachedBalance)
        assertNull(restarted.state.value.balance)
        restarted.wipe().getOrThrow()
        assertNull(cache.data.first().usdtDisplayBalance)
    }

    @Test
    fun `display cache write failure does not prevent credential and wallet cleanup`() = test {
        val repo = UsdtRepo(service, failingCache(), testDispatcher)
        val pending: UsdtDeposit = mock {
            on { id }.thenReturn("pending")
            on { status }.thenReturn("confirming")
        }
        repo.updateDeposits(listOf(pending))
        var credentialsCleared = false

        repo.wipe { credentialsCleared = true }.getOrThrow()

        assertTrue(credentialsCleared)
        verify(service).wipe()
        assertEquals(UsdtWalletState(), repo.state.value)
        val walletError = UsdtException.Storage("wallet cleanup failed")
        whenever(service.wipe()).thenAnswer { throw walletError }
        assertEquals(walletError, repo.wipe().exceptionOrNull())
    }

    @Test
    fun `display cache write failure does not leave old state after wallet restoration`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(service.wallet(requiresNetwork = false)).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("original")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        whenever(wallet.refreshTransfers()).thenReturn(emptyList())
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        val repo = UsdtRepo(service, failingCache(), testDispatcher)
        repo.refresh(history = false, force = true).getOrThrow()
        whenever(wallet.receiveAddress()).thenReturn("restored")
        whenever(wallet.history()).thenReturn(listOf(incoming("restored-payment")))

        repo.restoreBackup("snapshot").getOrThrow()

        verify(wallet).restoreBackup("snapshot")
        assertEquals("restored", repo.state.value.address)
        assertEquals(listOf(incoming("restored-payment")), repo.state.value.transfers)
        assertNull(repo.state.value.balance)
        assertNull(repo.state.value.cachedBalance)
        assertTrue(repo.state.value.balanceIsStale)
    }

    private fun failingCache() = CacheStore(object : DataStore<AppCacheData> {
        override val data = flowOf(AppCacheData())
        override suspend fun updateData(transform: suspend (AppCacheData) -> AppCacheData): AppCacheData =
            throw IOException("cache unavailable")
    })

    @Test
    fun `quote rate limits pause manual payment retries and refresh until the cooldown expires`() = test {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val clock = object : Clock { override fun now() = now }
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("sender")
        whenever(wallet.quoteMaxTransfer("recipient", UsdtDestination.ARBITRUM))
            .thenThrow(UsdtException.RateLimited()).thenReturn(quote())
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher, clock)
        assertTrue(
            repo.quoteMaximum("recipient", UsdtDestination.ARBITRUM).exceptionOrNull() is UsdtException.RateLimited
        )
        now += 59.seconds
        assertTrue(
            repo.quote("recipient", "1", UsdtDestination.ARBITRUM).exceptionOrNull() is UsdtException.RateLimited
        )
        assertTrue(
            repo.quoteMaximum("recipient", UsdtDestination.ARBITRUM).exceptionOrNull() is UsdtException.RateLimited
        )
        assertTrue(
            repo.send(quote()) { error("Submission must not start during cooldown") }
                .exceptionOrNull() is UsdtException.RateLimited
        )
        assertTrue(repo.refresh(true, force = true).exceptionOrNull() is UsdtException.RateLimited)
        verify(wallet, times(1)).quoteMaxTransfer("recipient", UsdtDestination.ARBITRUM)
        now += 1.seconds
        assertTrue(repo.quoteMaximum("recipient", UsdtDestination.ARBITRUM).isSuccess)
        verify(wallet, times(2)).quoteMaxTransfer("recipient", UsdtDestination.ARBITRUM)
    }

    @Test
    fun `unused disabled wallet does not refresh but stored recovery and opt in do`() {
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertEquals(false, repo.shouldRefresh(enabled = false))
        assertTrue(repo.shouldRefresh(enabled = true))
        whenever(service.hasStoredWallet).thenReturn(true)
        assertTrue(repo.shouldRefresh(enabled = false))
        verify(service, never()).wallet()
    }

    @Test
    fun `own address has a specific error for exact and maximum sends`() = test {
        val address = "0x18503bD685aF84E6A7bC289898bAa1C3f5d8527E"
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn(address)
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        val recipient = " ${address.lowercase()} "
        assertTrue(repo.quote(recipient, "1", UsdtDestination.ARBITRUM).exceptionOrNull() is UsdtSelfSendError)
        assertTrue(repo.quoteMaximum(recipient, UsdtDestination.ARBITRUM).exceptionOrNull() is UsdtSelfSendError)
        verify(wallet, never()).quoteTransfer(recipient, 1_000_000u, UsdtDestination.ARBITRUM)
        verify(wallet, never()).quoteMaxTransfer(recipient, UsdtDestination.ARBITRUM)

        mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
            native.`when`<ULong> { usdtParseAmount("1") }.thenReturn(1_000_000u)
            val other = "0x1111111111111111111111111111111111111111"
            val quote = quote()
            whenever(wallet.quoteTransfer(other, 1_000_000u, UsdtDestination.ARBITRUM)).thenReturn(quote)
            assertEquals(quote, repo.quote(other, "1", UsdtDestination.ARBITRUM).getOrThrow())
        }
    }

    @Test
    fun `known unsettled deposits protect the wallet until their updated status is settled`() = test {
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        val pending: UsdtDeposit = mock {
            on { id }.thenReturn("deposit-one")
            on { status }.thenReturn("confirming")
        }
        val second: UsdtDeposit = mock {
            on { id }.thenReturn("deposit-two")
            on { status }.thenReturn("refunding")
        }
        repo.updateDeposits(listOf(pending, second))
        assertEquals(setOf("deposit-one", "deposit-two"), repo.state.value.pendingDepositIds)
        assertTrue(repo.state.value.hasFundsOrPendingTransfers)
        whenever(pending.status).thenReturn("completed")
        repo.updateDeposits(listOf(pending))
        assertEquals(setOf("deposit-two"), repo.state.value.pendingDepositIds)
        whenever(second.status).thenReturn("refunded")
        repo.updateDeposits(listOf(second))
        assertTrue(repo.state.value.pendingDepositIds.isEmpty())
    }

    @Test
    fun `deposit operations finish before wipe clears wallet credentials`() = test {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        whenever(service.depositNetworks()).doSuspendableAnswer {
            started.complete(Unit)
            finish.await()
            emptyList()
        }
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        val deposits = UsdtDepositRepo(repo)
        val request = launch { deposits.depositNetworks().getOrThrow() }
        started.await()
        var credentialsCleared = false
        val wipe = launch { repo.wipe { credentialsCleared = true }.getOrThrow() }
        runCurrent()
        assertEquals(false, credentialsCleared)
        verify(service, never()).wipe()

        finish.complete(Unit)
        request.join()
        wipe.join()

        assertTrue(credentialsCleared)
        verify(service).wipe()
    }

    @Test
    fun `saved activity is published before an offline balance refresh fails`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(listOf(transfer))
        whenever(wallet.balance()).thenThrow(UsdtException.NetworkUnavailable())
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertTrue(repo.refresh(true, force = true).isFailure)
        assertEquals(listOf(transfer), repo.state.value.transfers)
        assertEquals("address", repo.state.value.address)
    }

    @Test
    fun `history still updates after settlement fails`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList(), listOf(incoming("received")))
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        whenever(wallet.refreshTransfers()).thenThrow(UsdtException.NetworkUnavailable())
        whenever(wallet.syncHistory()).thenReturn(true)
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertTrue(repo.refresh(true, force = true).isFailure)
        assertEquals(listOf(incoming("received")), repo.state.value.transfers)
        assertTrue(repo.state.value.historyComplete)
        verify(wallet).syncHistory()
    }

    @Test
    fun `throttled settlement does not request history`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        whenever(wallet.refreshTransfers()).thenThrow(UsdtException.RateLimited())
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertTrue(repo.refresh(true, force = true).exceptionOrNull() is UsdtException.RateLimited)
        verify(wallet, never()).syncHistory()
    }

    @Test
    fun `forced refresh reports cooldown instead of accepting cached balance`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        doReturn(0uL).whenever(wallet).balance()
        whenever(wallet.refreshTransfers()).thenThrow(UsdtException.RateLimited())
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertTrue(repo.refresh(history = false, force = true).isFailure)
        assertTrue(repo.refresh(history = false, force = true).exceptionOrNull() is UsdtException.RateLimited)
        verify(wallet).balance()
        assertTrue(repo.refresh(history = false).isSuccess)
    }

    @Test
    fun `send presentation defers history without stopping settlement and resumes after dismissal`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        whenever(wallet.refreshTransfers()).thenReturn(emptyList())
        whenever(wallet.syncHistory()).thenReturn(true)
        val balance = CompletableDeferred<ULong>()
        whenever(wallet.balance()).doSuspendableAnswer { balance.await() }
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        val refresh = launch { repo.refresh(true, force = true).getOrThrow() }
        runCurrent()
        repo.isSendPresented = true
        balance.complete(1_000_000uL)
        refresh.join()
        assertEquals(1_000_000uL, repo.state.value.balance)
        verify(wallet).refreshTransfers()
        verify(wallet, never()).syncHistory()
        assertEquals(false, repo.state.value.historyComplete)

        repo.isSendPresented = false
        repo.refresh(true, force = true).getOrThrow()
        verify(wallet).syncHistory()
        assertTrue(repo.state.value.historyComplete)
    }

    @Test
    fun `send authorizes after queued wallet work and never dispatches expired terms`() = test {
        val release = CompletableDeferred<Unit>()
        whenever(service.depositNetworks()).doSuspendableAnswer {
            release.await()
            emptyList()
        }
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        val preceding = launch { UsdtDepositRepo(repo).depositNetworks().getOrThrow() }
        runCurrent()
        val quote = quote()
        var authorized = false
        var expired = false
        val send = launch {
            val result = repo.send(quote) {
                authorized = true
                if (expired) throw PaykitPaymentRequestError.RequestExpired
            }
            assertEquals(PaykitPaymentRequestError.RequestExpired, result.exceptionOrNull())
        }
        runCurrent()
        assertEquals(false, authorized)
        expired = true
        release.complete(Unit)
        preceding.join()
        send.join()
        assertTrue(authorized)
        verify(service, never()).send(quote)
    }

    @Test
    fun `pending submission stays successful when subsequent history is unreadable`() = test {
        val quote = quote()
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.history()).thenThrow(UsdtException.Storage("unreadable history"))
        val submitted = incoming("submitted").copy(
            isIncoming = false,
            status = UsdtTransferStatus.PENDING,
            txHash = null,
        )
        val deadline = Instant.parse("2026-10-12T12:00:00.123456789Z")
        whenever(service.send(quote, deadline)).thenReturn(submitted)
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        assertEquals(submitted, repo.send(quote, deadline).getOrThrow())
        verify(service).send(quote, deadline)
        assertEquals(listOf(submitted), repo.state.value.transfers)
    }

    @Test
    fun `failed send publishes its durably queued transfer and preserves the error`() = test {
        val quote = quote()
        val error = UsdtException.BackupUnavailable()
        val queued = incoming("queued").copy(
            isIncoming = false,
            status = UsdtTransferStatus.PENDING,
            txHash = null,
        )
        whenever(service.send(quote)).thenAnswer { throw error }
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.history()).thenReturn(listOf(queued))
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)

        assertEquals(error, repo.send(quote).exceptionOrNull())
        assertEquals(listOf(queued), repo.state.value.transfers)
        verify(service).send(quote)
    }

    @Test
    fun `fee quote expiration while waiting for wallet work prevents submission`() = test {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val clock = object : Clock { override fun now() = now }
        val release = CompletableDeferred<Unit>()
        whenever(service.depositNetworks()).doSuspendableAnswer {
            release.await()
            emptyList()
        }
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher, clock)
        val preceding = launch { UsdtDepositRepo(repo).depositNetworks().getOrThrow() }
        val quote = quote(now.epochSeconds.toULong() + 120u)
        var authorized = false
        val send = launch {
            val result = repo.send(quote) { authorized = true }
            assertTrue(result.exceptionOrNull() is UsdtException.QuoteExpired)
        }
        runCurrent()
        assertEquals(false, authorized)
        now += 116.seconds
        release.complete(Unit)
        preceding.join()
        send.join()
        assertTrue(authorized)
        verify(service, never()).send(quote)
    }

    @Test
    fun `fee quote is rechecked after authorization finishes`() = test {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val clock = object : Clock { override fun now() = now }
        val quote = quote(now.epochSeconds.toULong() + 120u)
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher, clock)

        val result = repo.send(quote) { now += 120.seconds }

        assertTrue(result.exceptionOrNull() is UsdtException.QuoteExpired)
        verify(service, never()).send(quote)
    }

    @Test
    fun `only new confirmed receipts celebrate after initial sync`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        whenever(wallet.syncHistory()).thenReturn(true)
        val old = incoming("old")
        var history = listOf(old)
        whenever(wallet.history()).thenAnswer { history }
        whenever(wallet.refreshTransfers()).thenAnswer { history }
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        repo.refresh(true, force = true).getOrThrow()
        assertTrue(repo.state.value.pendingReceipts.isEmpty())
        val received = incoming("new")
        val pending = incoming("pending").copy(status = UsdtTransferStatus.PENDING, txHash = null)
        history = listOf(
            old, received, pending, incoming("sent").copy(isIncoming = false),
            incoming("failed").copy(status = UsdtTransferStatus.FAILED), incoming("zero").copy(amount = 0uL)
        )
        repo.refresh(true, force = true).getOrThrow()
        assertEquals(listOf(received), repo.state.value.pendingReceipts)
        repo.refresh(true, force = true).getOrThrow()
        assertEquals(listOf(received), repo.state.value.pendingReceipts)
        val confirmed = pending.copy(status = UsdtTransferStatus.CONFIRMED, txHash = "pending")
        history = history.map { if (it.id == confirmed.id) confirmed else it }
        repo.refresh(true, force = true).getOrThrow()
        assertEquals(listOf(received, confirmed), repo.state.value.pendingReceipts)
        assertTrue(repo.consumeReceipt(received.id))
        assertFalse(repo.consumeReceipt(received.id))
        repo.refresh(true, force = true).getOrThrow()
        assertEquals(listOf(confirmed), repo.state.value.pendingReceipts)
        whenever(wallet.receiveAddress()).thenReturn("another wallet")
        history = listOf(incoming("restored"))
        repo.refresh(true, force = true).getOrThrow()
        assertTrue(repo.state.value.pendingReceipts.isEmpty())
        assertFalse(repo.consumeReceipt(confirmed.id))
    }

    @Test
    fun `partial history remains visible without declaring initial sync complete`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(listOf(incoming("old")))
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        whenever(wallet.refreshTransfers()).thenReturn(emptyList())
        whenever(wallet.syncHistory()).thenReturn(false, true)
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        repo.refresh(true, force = true).getOrThrow()
        assertEquals(1_000_000uL, repo.state.value.balance)
        assertEquals(listOf(incoming("old")), repo.state.value.transfers)
        assertEquals(false, repo.state.value.historyComplete)
        repo.refresh(true, force = true).getOrThrow()
        assertTrue(repo.state.value.historyComplete)
    }

    @Test
    fun `execution checks bypass normal cooldown and preserve rate limit backoff`() = test {
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.receiveAddress()).thenReturn("address")
        whenever(wallet.receiveUri()).thenReturn("uri")
        whenever(wallet.history()).thenReturn(emptyList())
        whenever(wallet.balance()).thenReturn(1_000_000uL)
        whenever(wallet.refreshTransfers()).thenReturn(emptyList())
        val sent = incoming("sent").copy(isIncoming = false)
        whenever(wallet.checkRecentExecution("sent")).thenReturn(sent)
        whenever(wallet.checkRecentExecution("limited")).thenThrow(UsdtException.RateLimited())
        val repo = UsdtRepo(service, CacheStore(InMemoryDataStore(AppCacheData())), testDispatcher)
        repo.refresh(false).getOrThrow()
        repo.waitForTransfer("sent").getOrThrow()
        assertEquals(listOf(sent), repo.state.value.transfers)
        assertTrue(repo.waitForTransfer("limited").exceptionOrNull() is UsdtException.RateLimited)
        repo.waitForTransfer("backoff").getOrThrow()
        verify(wallet, never()).checkRecentExecution("backoff")
        assertTrue(repo.refresh(true, force = true).exceptionOrNull() is UsdtException.RateLimited)
        verify(wallet, never()).syncHistory()
    }

    private fun incoming(id: String) = UsdtTransfer(
        sender = null,
        id = id, txHash = id, userOperationHash = null, bridgeGuid = null, orchestra = null, recipient = "recipient",
        destination = UsdtDestination.ARBITRUM, amount = 1_000_000uL, receivedAmount = 1_000_000uL,
        fee = null, isIncoming = true, status = UsdtTransferStatus.CONFIRMED,
        timestamp = 1uL,
    )
}
