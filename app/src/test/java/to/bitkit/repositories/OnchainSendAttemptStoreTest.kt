package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Test
import to.bitkit.services.LightningService
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnchainSendAttemptStoreTest : BaseUnitTest() {
    private val key = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name

    @Test
    fun `reopen blocks unresolved send until exact transaction is observed and followup is complete`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            saved = it.getArgument(1)
        }
        val firstStore = OnchainSendAttemptStore(testDispatcher, keychain, mock())
        val attempt = firstStore.admitForTest()
        firstStore.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown("ab".repeat(32)), attempt.walletIndex)

        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock())
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        assertNull(reopened.observeExactTransaction("cd".repeat(32)))
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }

        val observed = reopened.observeExactTransaction("ab".repeat(32))
        assertEquals(OnchainSendEvidence.Observed, observed?.evidence)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        reopened.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
        assertTrue(reopened.admitForTest().attemptId != attempt.attemptId)
    }

    @Test
    fun `corrupt attempt refuses a new send without replacing evidence`() = test {
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenReturn("not-json")
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock())

        assertFailsWith<OnchainSendAttemptUnreadableError> { store.admitForTest() }
        verify(keychain).loadString(key, 0)
    }

    @Test
    fun `outcome remains in the admitted node wallet after config context changes`() = test {
        val values = mutableMapOf<Int, String>()
        val keychain = mock<Keychain>()
        val service = mock<LightningService>()
        var currentIndex = 7
        whenever(service.currentWalletIndex).thenAnswer { currentIndex }
        whenever(keychain.loadString(eq(key), any())).thenAnswer { values[it.getArgument(1)] }
        whenever(keychain.upsertString(eq(key), any(), any())).doSuspendableAnswer {
            values[it.getArgument(2)] = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service)
        val attempt = store.admitForTest()
        assertEquals(7, attempt.walletIndex)
        currentIndex = 8
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), attempt.walletIndex)
        assertNull(store.current())
        currentIndex = 7
        assertEquals(OnchainSendEvidence.Accepted, store.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
    }

    @Test
    fun `parallel admissions dispatch only one before-send callback`() = test {
        var saved: String? = null
        var callbacks = 0
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock())
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async { store.admitForTest { callbacks++; entered.complete(Unit); finish.await() } }
        entered.await()
        val second = async { runCatching { store.admitForTest { callbacks++ } } }
        finish.complete(Unit)
        first.await()
        assertTrue(second.await().exceptionOrNull() is OnchainSendBlockedError)
        assertEquals(1, callbacks)
    }

    @Test
    fun `cancel after admission and failed result save preserve durable pending guard`() = test {
        var saved: String? = null
        var failWrite = false
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            if (failWrite) error("storage unavailable")
            saved = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock())
        val admitted = CompletableDeferred<OnchainSendAttempt>()
        val send = launch { admitted.complete(store.admitForTest()); kotlinx.coroutines.awaitCancellation() }
        val attempt = admitted.await()
        send.cancelAndJoin()
        failWrite = true
        assertFailsWith<IllegalStateException> {
            store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), attempt.walletIndex)
        }
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock())
        assertEquals(OnchainSendEvidence.Pending, reopened.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
    }

    private suspend fun OnchainSendAttemptStore.admitForTest(beforeSendAttempt: suspend () -> Unit = {}): OnchainSendAttempt = admit(
        walletId = "wallet-1",
        requestId = null,
        orderId = null,
        address = "bcrt1qrecipient",
        amountSats = 1_000uL,
        isMaxAmount = false,
        feeRateSatsPerVByte = 1uL,
        isTransfer = false,
        channelId = null,
        tags = emptyList(),
        beforeSendAttempt = beforeSendAttempt,
    )
}
