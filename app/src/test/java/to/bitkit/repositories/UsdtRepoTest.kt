package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.UsdtWallet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UsdtRepoTest : BaseUnitTest() {
    private val service: UsdtService = mock()
    private val wallet: UsdtWallet = mock()
    private val transfer: UsdtTransfer = mock()

    @Test
    fun `deposit operations finish before wipe clears wallet credentials`() = test {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        whenever(service.depositNetworks()).doSuspendableAnswer {
            started.complete(Unit)
            finish.await()
            emptyList()
        }
        val repo = UsdtRepo(service, testDispatcher)
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
        val repo = UsdtRepo(service, testDispatcher)
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
        val repo = UsdtRepo(service, testDispatcher)
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
        val repo = UsdtRepo(service, testDispatcher)
        assertTrue(repo.refresh(true, force = true).exceptionOrNull() is UsdtException.RateLimited)
        verify(wallet, never()).syncHistory()
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
        val repo = UsdtRepo(service, testDispatcher)
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
        val repo = UsdtRepo(service, testDispatcher)
        val preceding = launch { UsdtDepositRepo(repo).depositNetworks().getOrThrow() }
        runCurrent()
        val quote: UsdtQuote = mock()
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
        val quote: UsdtQuote = mock()
        whenever(service.wallet()).thenReturn(wallet)
        whenever(wallet.history()).thenThrow(UsdtException.Storage("unreadable history"))
        val submitted = incoming("submitted").copy(
            isIncoming = false,
            status = UsdtTransferStatus.PENDING,
            txHash = null,
        )
        whenever(service.send(quote)).thenReturn(submitted)
        val repo = UsdtRepo(service, testDispatcher)
        assertEquals(submitted, repo.send(quote).getOrThrow())
        assertEquals(listOf(submitted), repo.state.value.transfers)
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
        val repo = UsdtRepo(service, testDispatcher)
        val events = mutableListOf<UsdtTransfer>()
        val collector = launch { repo.receivedTxs.collect { events.add(it) } }
        runCurrent()
        try {
            repo.refresh(true, force = true).getOrThrow()
            runCurrent()
            assertTrue(events.isEmpty())
            val received = incoming("new")
            val pending = incoming("pending").copy(status = UsdtTransferStatus.PENDING, txHash = null)
            history = listOf(
                old, received, pending, incoming("sent").copy(isIncoming = false),
                incoming("failed").copy(status = UsdtTransferStatus.FAILED), incoming("zero").copy(amount = 0uL)
            )
            repo.refresh(true, force = true).getOrThrow()
            runCurrent()
            assertEquals(listOf(received), events)
            repo.refresh(true, force = true).getOrThrow()
            runCurrent()
            assertEquals(listOf(received), events)
            val confirmed = pending.copy(status = UsdtTransferStatus.CONFIRMED, txHash = "pending")
            history = history.map { if (it.id == confirmed.id) confirmed else it }
            repo.refresh(true, force = true).getOrThrow()
            runCurrent()
            assertEquals(listOf(received, confirmed), events)
            whenever(wallet.receiveAddress()).thenReturn("another wallet")
            history = listOf(incoming("restored"))
            repo.refresh(true, force = true).getOrThrow()
            runCurrent()
            assertEquals(listOf(received, confirmed), events)
        } finally {
            collector.cancel()
        }
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
        val repo = UsdtRepo(service, testDispatcher)
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
        val repo = UsdtRepo(service, testDispatcher)
        repo.refresh(false).getOrThrow()
        repo.waitForTransfer("sent").getOrThrow()
        assertEquals(listOf(sent), repo.state.value.transfers)
        assertTrue(repo.waitForTransfer("limited").exceptionOrNull() is UsdtException.RateLimited)
        repo.waitForTransfer("backoff").getOrThrow()
        verify(wallet, never()).checkRecentExecution("backoff")
        repo.refresh(true, force = true).getOrThrow()
        verify(wallet, never()).syncHistory()
    }

    private fun incoming(id: String) = UsdtTransfer(
        id = id, txHash = id, userOperationHash = null, bridgeGuid = null, orchestra = null, recipient = "recipient",
        destination = UsdtDestination.ARBITRUM, amount = 1_000_000uL, receivedAmount = 1_000_000uL,
        fee = null, isIncoming = true, status = UsdtTransferStatus.CONFIRMED,
        timestamp = 1uL,
    )
}
