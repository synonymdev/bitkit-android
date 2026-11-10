package to.bitkit.repositories

import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.PubkySwapRouteStatus
import com.synonym.bitkitcore.pubkySwapsAdvance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    private lateinit var sut: PubkySwapsRouteRepo

    @Before
    fun setUp() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("mnemonic")
        sut = PubkySwapsRouteRepo(
            ioDispatcher = testDispatcher,
            keychain = keychain,
            pubkyRepo = mock(),
            lightningRepo = mock(),
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
