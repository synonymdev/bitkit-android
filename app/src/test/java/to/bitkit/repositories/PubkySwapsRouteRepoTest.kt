package to.bitkit.repositories

import com.synonym.bitkitcore.PubkySwapRestoreReport
import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.PubkySwapRouteStatus
import com.synonym.bitkitcore.pubkySwapsAdvance
import com.synonym.bitkitcore.pubkySwapsRestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyProfile
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PubkySwapsRouteRepoTest : BaseUnitTest() {
    companion object {
        private const val SWAP_ID = "swap"
        private const val CLAIM_TXID = "claim"
        private const val CONFIRMED_CLAIM_TXID = "confirmed"

        /** The Kotlin facade holding bitkit-core's top-level functions, which Kotlin sources cannot name. */
        private val CORE: Class<*> = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    }

    private val keychain = mock<Keychain>()
    private val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val pubkyRepo = mock<PubkyRepo> { on { contacts } doReturn contacts }
    private val lightningRepo = mock<LightningRepo>()
    private lateinit var sut: PubkySwapsRouteRepo

    @Before
    fun setUp() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("mnemonic")
        sut = PubkySwapsRouteRepo(
            ioDispatcher = testDispatcher,
            keychain = keychain,
            pubkyRepo = pubkyRepo,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `awaitClaim finishes once the claim is broadcast`() = test {
        val txid = advancing(
            PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u),
            PubkySwapRouteState.ClaimBroadcast(CLAIM_TXID),
            PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID),
        ) { sut.awaitClaim(SWAP_ID, timeout = 1.minutes) }

        assertEquals(Result.success(CLAIM_TXID), txid)
    }

    @Test
    fun `awaitClaim keeps polling after a failed advance`() = test {
        val txid = advancing(
            AppError("electrum unreachable"),
            PubkySwapRouteState.ClaimBroadcast(CLAIM_TXID),
            PubkySwapRouteState.Claimed(CLAIM_TXID),
        ) { sut.awaitClaim(SWAP_ID, timeout = 1.minutes) }

        assertEquals(Result.success(CLAIM_TXID), txid)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `awaitClaim running out leaves the swap open and keeps advancing it`() = test {
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u)))
                .thenReturn(status(PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID)))

            val result = sut.awaitClaim(SWAP_ID, timeout = 1.seconds)
            advanceUntilIdle()

            assertIs<PubkySwapClaimTimeoutException>(result.exceptionOrNull())
            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, times(2))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `awaitClaim keeps advancing the swap after its caller is cancelled`() = test {
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u)))
                .thenReturn(status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u)))
                .thenReturn(status(PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID)))

            val caller = launch { sut.awaitClaim(SWAP_ID) }
            runCurrent()
            caller.cancel()
            advanceUntilIdle()

            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, atLeast(3))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `awaitClaim stops advancing the swap once its hold payment fails`() = test {
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u)))
            val holdFailure = CompletableDeferred<Unit>()

            val result = async { sut.awaitClaim(SWAP_ID, holdFailure = holdFailure) }
            runCurrent()
            holdFailure.complete(Unit)
            advanceUntilIdle()

            assertIs<PubkySwapHoldFailedException>(result.await().exceptionOrNull())
            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, times(1))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `awaitClaim keeps advancing the swap when its hold payment watch is cancelled`() = test {
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u)))
                .thenReturn(status(PubkySwapRouteState.ClaimBroadcast(CLAIM_TXID)))
                .thenReturn(status(PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID)))
            val holdFailure = CompletableDeferred<Unit>()

            val result = async { sut.awaitClaim(SWAP_ID, holdFailure = holdFailure) }
            runCurrent()
            holdFailure.cancel()
            advanceUntilIdle()

            assertEquals(Result.success(CLAIM_TXID), result.await())
        }
    }

    @Test
    fun `restoredSwaps asks again only the providers it read in part`() = test {
        contacts.value = listOf(PubkyProfile.placeholder("provider-a"), PubkyProfile.placeholder("provider-b"))
        val asked = mutableListOf<List<String>>()
        val swaps = restoring(
            asked,
            report(restored = listOf("swap-a"), incomplete = listOf("provider-a")),
            report(restored = listOf("swap-a", "swap-a2")),
        ) { sut.restoredSwaps().take(2).toList() }

        assertEquals(listOf(listOf("swap-a"), listOf("swap-a2")), swaps.map { it.restored })
        assertEquals(listOf("provider-a"), asked.last())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoredSwaps asks the providers past bitkit-core's limit in another call`() = test {
        val keys = (1..31).map { "provider-$it" }
        contacts.value = keys.map(PubkyProfile::placeholder)
        val asked = mutableListOf<List<String>>()
        restoring(asked, report()) {
            backgroundScope.launch { sut.restoredSwaps().collect {} }
            advanceUntilIdle()
        }

        assertEquals(keys.toSet(), asked.flatten().toSet())
        assertTrue(asked.all { it.size <= 30 })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoredSwaps restores again for contacts that load later`() = test {
        val asked = mutableListOf<List<String>>()
        restoring(asked, report()) {
            backgroundScope.launch { sut.restoredSwaps().collect {} }
            advanceUntilIdle()
            contacts.value = listOf(PubkyProfile.placeholder("provider-late"))
            advanceUntilIdle()
        }

        assertEquals(listOf("provider-late"), asked.last())
    }

    private suspend fun <T> restoring(
        asked: MutableList<List<String>>,
        vararg reports: PubkySwapRestoreReport,
        block: suspend () -> T,
    ): T {
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("bcrt1qpayout"))
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))
        return mockStatic(CORE).use { core ->
            var call = 0
            core.`when`<Any> {
                runBlocking {
                    pubkySwapsRestore(any(), any(), any(), any(), eq(2uL), any(), any(), anyOrNull())
                }
            }.thenAnswer {
                @Suppress("UNCHECKED_CAST")
                asked += it.arguments[0] as List<String>
                reports[minOf(call++, reports.lastIndex)]
            }
            block()
        }
    }

    private fun report(
        restored: List<String> = emptyList(),
        incomplete: List<String> = emptyList(),
    ) = PubkySwapRestoreReport(
        restored = restored,
        known = emptyList(),
        finished = emptyList(),
        incompleteProviders = incomplete,
    )

    private suspend fun <T> advancing(vararg answers: Any, block: suspend () -> T): T =
        mockStatic(CORE).use { core ->
            var stub = core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
            answers.forEach { answer ->
                stub = when (answer) {
                    is Throwable -> stub.thenAnswer { throw answer }
                    else -> stub.thenReturn(status(answer as PubkySwapRouteState))
                }
            }
            block()
        }

    private fun status(state: PubkySwapRouteState) = PubkySwapRouteStatus(
        swapId = SWAP_ID,
        state = state,
        providerPubky = "provider",
        deliveredSat = 20_000u,
        destinationAddress = "address",
    )
}
