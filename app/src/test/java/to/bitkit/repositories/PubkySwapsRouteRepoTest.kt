package to.bitkit.repositories

import com.synonym.bitkitcore.PubkySwapException
import com.synonym.bitkitcore.PubkySwapRestoreReport
import com.synonym.bitkitcore.PubkySwapRoutePayment
import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.PubkySwapRouteStatus
import com.synonym.bitkitcore.pubkySwapsAdvance
import com.synonym.bitkitcore.pubkySwapsCommitRoute
import com.synonym.bitkitcore.pubkySwapsRestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyProfile
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import breez_sdk_spark.PaymentStatus as SparkPaymentStatus

class PubkySwapsRouteRepoTest : BaseUnitTest() {
    companion object {
        private const val SWAP_ID = "swap"
        private const val CLAIM_TXID = "claim"
        private const val CONFIRMED_CLAIM_TXID = "confirmed"
        private const val HOLD_HASH = "hold"

        /** The Kotlin facade holding bitkit-core's top-level functions, which Kotlin sources cannot name. */
        private val CORE: Class<*> = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    }

    private val keychain = mock<Keychain>()
    private val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val pubkyRepo = mock<PubkyRepo> { on { contacts } doReturn contacts }
    private val nodeEvents = MutableSharedFlow<Event>()
    private val lightningRepo = mock<LightningRepo> { on { nodeEvents } doReturn nodeEvents }
    private val cacheData = MutableStateFlow(AppCacheData())
    private val cacheStore = mock<CacheStore> { on { data } doReturn cacheData }
    private lateinit var sut: PubkySwapsRouteRepo

    @Before
    fun setUp() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("mnemonic")
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.update(transform)
        }
        sut = PubkySwapsRouteRepo(
            ioDispatcher = testDispatcher,
            keychain = keychain,
            pubkyRepo = pubkyRepo,
            lightningRepo = lightningRepo,
            cacheStore = cacheStore,
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
            cacheData.value = AppCacheData(unfinishedPubkySwaps = listOf(SWAP_ID))

            val result = async { sut.awaitClaim(SWAP_ID, holdFailure = holdFailure) }
            runCurrent()
            holdFailure.complete(Unit)
            advanceUntilIdle()

            assertIs<PubkySwapHoldFailedException>(result.await().exceptionOrNull())
            // The provider never funds a swap whose hold payment failed, so no later start resumes it.
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `commit keeps the swap to resume until its claim confirms`() = test {
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsCommitRoute(any(), any(), any(), anyOrNull()) } }
                .thenReturn(swapPayment())
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.ClaimBroadcast(CLAIM_TXID)))
                .thenReturn(status(PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID)))

            sut.commit("quote")
            assertEquals(listOf(SWAP_ID), cacheData.value.unfinishedPubkySwaps)

            sut.awaitClaim(SWAP_ID)
            advanceUntilIdle()
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resumeUnfinishedSwaps advances a stored swap past an hour until its claim confirms`() = test {
        cacheData.value = AppCacheData(unfinishedPubkySwaps = listOf(SWAP_ID))
        mockStatic(CORE).use { core ->
            var calls = 0
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }.thenAnswer {
                // At 30 s apart, 150 unconfirmed claims outlast an hour.
                if (++calls <= 150) {
                    status(PubkySwapRouteState.ClaimBroadcast(CLAIM_TXID))
                } else {
                    status(PubkySwapRouteState.Claimed(CONFIRMED_CLAIM_TXID))
                }
            }

            sut.resumeUnfinishedSwaps()
            advanceUntilIdle()

            assertEquals(151, calls)
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resumeUnfinishedSwaps drops a swap that failed`() = test {
        cacheData.value = AppCacheData(unfinishedPubkySwaps = listOf(SWAP_ID))
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenReturn(status(PubkySwapRouteState.Failed("refunded")))

            sut.resumeUnfinishedSwaps()
            advanceUntilIdle()

            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resumeUnfinishedSwaps drops a swap bitkit-core has no record of`() = test {
        cacheData.value = AppCacheData(unfinishedPubkySwaps = listOf(SWAP_ID))
        mockStatic(CORE).use { core ->
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }
                .thenThrow(PubkySwapException.NotFound("no swap record"))

            sut.resumeUnfinishedSwaps()
            advanceUntilIdle()

            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, times(1))
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `dropFailedSwap stops advancing a swap after its claim wait ran out`() = test {
        cacheData.value = AppCacheData(unfinishedPubkySwaps = listOf(SWAP_ID))
        mockStatic(CORE).use { core ->
            var calls = 0
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }.thenAnswer {
                calls++
                status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u))
            }

            sut.awaitClaim(SWAP_ID, timeout = 1.seconds)
            sut.dropFailedSwap(SWAP_ID)
            val callsAtDrop = calls
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals(callsAtDrop, calls)
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoredSwaps reveals one payout address for all its restores`() = test {
        contacts.value = listOf(PubkyProfile.placeholder("provider-a"))
        val asked = mutableListOf<List<String>>()
        restoring(asked, report(incomplete = listOf("provider-a"))) {
            backgroundScope.launch { sut.restoredSwaps().collect {} }
            advanceTimeBy(5.minutes)
            contacts.value = listOf(PubkyProfile.placeholder("provider-a"), PubkyProfile.placeholder("provider-b"))
            advanceTimeBy(5.minutes)
        }

        assertEquals(6, asked.size)
        verify(lightningRepo, times(1)).newAddress()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoredSwaps after a wipe asks again the providers the wiped wallet read`() = test {
        contacts.value = listOf(PubkyProfile.placeholder("provider-a"))
        val asked = mutableListOf<List<String>>()
        restoring(asked, report()) {
            val first = backgroundScope.launch { sut.restoredSwaps().collect {} }
            advanceUntilIdle()
            sut.resetForWipe()
            advanceUntilIdle()
            assertTrue(first.isCompleted)

            backgroundScope.launch { sut.restoredSwaps().collect {} }
            advanceUntilIdle()
        }

        assertEquals(listOf(listOf("provider-a"), listOf("provider-a")), asked)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resumeUnfinishedSwaps drops a swap whose hold payment failed while the app was closed`() = test {
        cacheData.value = AppCacheData(
            unfinishedPubkySwaps = listOf(SWAP_ID),
            pubkySwapHoldHashes = mapOf(SWAP_ID to HOLD_HASH),
        )
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(listOf(holdPayment(PaymentStatus.FAILED))))
        whenever(lightningRepo.sparkLightningSend(HOLD_HASH)).thenReturn(SparkLightningSend.NotFound)
        mockStatic(CORE).use { core ->
            sut.resumeUnfinishedSwaps()
            advanceUntilIdle()

            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, never())
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
            assertEquals(emptyMap(), cacheData.value.pubkySwapHoldHashes)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resumeUnfinishedSwaps drops a swap whose hold payment failed over spark`() = test {
        cacheData.value = AppCacheData(
            unfinishedPubkySwaps = listOf(SWAP_ID),
            pubkySwapHoldHashes = mapOf(SWAP_ID to HOLD_HASH),
        )
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))
        whenever(lightningRepo.sparkLightningSend(HOLD_HASH))
            .thenReturn(SparkLightningSend.Found(SparkPaymentStatus.FAILED, preimage = null))
        mockStatic(CORE).use { core ->
            sut.resumeUnfinishedSwaps()
            advanceUntilIdle()

            core.verify({ runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }, never())
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a hold payment failing stops its resumed swap`() = test {
        cacheData.value = AppCacheData(
            unfinishedPubkySwaps = listOf(SWAP_ID),
            pubkySwapHoldHashes = mapOf(SWAP_ID to HOLD_HASH),
        )
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(listOf(holdPayment(PaymentStatus.PENDING))))
        mockStatic(CORE).use { core ->
            var calls = 0
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }.thenAnswer {
                calls++
                status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u))
            }

            sut.resumeUnfinishedSwaps()
            runCurrent()
            nodeEvents.emit(Event.PaymentFailed(paymentId = HOLD_HASH, paymentHash = HOLD_HASH, reason = null))
            runCurrent()
            val callsAtFailure = calls
            advanceTimeBy(10.minutes)
            runCurrent()

            assertTrue(callsAtFailure > 0)
            assertEquals(callsAtFailure, calls)
            assertEquals(emptyList(), cacheData.value.unfinishedPubkySwaps)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `dropFailedSwap stops a running claim wait`() = test {
        mockStatic(CORE).use { core ->
            var calls = 0
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }.thenAnswer {
                calls++
                status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u))
            }

            val claim = async { sut.awaitClaim(SWAP_ID) }
            runCurrent()
            sut.dropFailedSwap(SWAP_ID)
            advanceTimeBy(5.seconds)
            runCurrent()
            val callsAfterDrop = calls
            advanceTimeBy(20.minutes)
            runCurrent()

            assertIs<PubkySwapHoldFailedException>(claim.await().exceptionOrNull())
            assertEquals(callsAfterDrop, calls)
            assertIs<PubkySwapHoldFailedException>(sut.awaitClaim(SWAP_ID).exceptionOrNull())
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `resetForWipe stops a running claim wait`() = test {
        mockStatic(CORE).use { core ->
            var calls = 0
            core.`when`<Any> { runBlocking { pubkySwapsAdvance(any(), any(), anyOrNull()) } }.thenAnswer {
                calls++
                status(PubkySwapRouteState.AwaitingLockup(confirmations = 0u, required = 1u))
            }

            val caller = launch { sut.awaitClaim(SWAP_ID) }
            runCurrent()
            sut.resetForWipe()
            runCurrent()
            val callsAtWipe = calls
            advanceTimeBy(20.minutes)
            runCurrent()

            assertEquals(callsAtWipe, calls)
            assertTrue(caller.isCompleted)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restoredSwaps leaves a swap dropped this session alone`() = test {
        contacts.value = listOf(PubkyProfile.placeholder("provider-a"))
        sut.dropFailedSwap(SWAP_ID)
        val asked = mutableListOf<List<String>>()
        val found = mutableListOf<RestoredPubkySwaps>()
        restoring(asked, report(known = listOf(SWAP_ID, "other-swap"))) {
            backgroundScope.launch { sut.restoredSwaps().collect { found += it } }
            advanceUntilIdle()
        }

        assertEquals(listOf(listOf("other-swap")), found.map { it.known })
        assertEquals(listOf("other-swap"), cacheData.value.unfinishedPubkySwaps)
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

    private fun swapPayment() = PubkySwapRoutePayment(
        swapId = SWAP_ID,
        invoice = "invoice",
        debitSat = 20_100u,
        deliveredSat = 20_000u,
        destinationAddress = "address",
        htlcAddress = "htlc",
        timeoutBlockHeight = 0u,
    )

    private fun report(
        restored: List<String> = emptyList(),
        incomplete: List<String> = emptyList(),
        known: List<String> = emptyList(),
    ) = PubkySwapRestoreReport(
        restored = restored,
        known = known,
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

    private fun holdPayment(status: PaymentStatus) = PaymentDetails(
        id = HOLD_HASH,
        kind = PaymentKind.Bolt11(
            hash = HOLD_HASH,
            preimage = null,
            secret = null,
            description = null,
            bolt11 = "bolt11"
        ),
        amountMsat = 1_000_000uL,
        feePaidMsat = null,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = 0uL,
    )
}
