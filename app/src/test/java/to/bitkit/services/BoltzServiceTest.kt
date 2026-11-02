package to.bitkit.services

import com.synonym.bitkitcore.BoltzEventListener
import com.synonym.bitkitcore.BoltzNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BoltzServiceTest : BaseUnitTest() {
    private companion object {
        val STOP_TIMEOUT = 30.seconds
    }

    private val keychain = mock<Keychain>()
    private val calls = mutableListOf<String>()
    private var startGate: CompletableDeferred<Unit>? = null
    private var stopFailure: Throwable? = null

    private val updatesStream = object : BoltzUpdatesStream {
        override suspend fun start(
            network: BoltzNetwork,
            listener: BoltzEventListener,
            mnemonic: String,
            passphrase: String?,
            feeRateSatPerVb: Double?,
            acceptZeroConf: Boolean,
        ) {
            startGate?.await()
            calls += "start"
        }

        override suspend fun stop() {
            stopFailure?.let { throw it }
            calls += "stop"
        }
    }

    private lateinit var sut: BoltzService

    @Before
    fun setUp() {
        whenever(keychain.loadString(any())).thenReturn("mnemonic")
        sut = BoltzService(keychain, mock(), updatesStream)
    }

    @Test
    fun `a stop runs only once a start in flight has landed`() = test {
        val gate = CompletableDeferred<Unit>().also { startGate = it }
        val start = launch { startUpdates() }
        runCurrent()

        val stop = launch { sut.stopUpdatesAndAwait() }
        runCurrent()
        assertTrue(calls.isEmpty())
        gate.complete(Unit)
        start.join()
        stop.join()

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `a start is refused while wiping and allowed once the wipe has ended`() = test {
        sut.setWiping(true)
        assertFailsWith<SwapUpdatesWipeInProgress> { startUpdates() }
        assertTrue(calls.isEmpty())

        sut.setWiping(false)
        startUpdates()

        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `a start already waiting for the stream is refused once the wipe began`() = test {
        val gate = CompletableDeferred<Unit>().also { startGate = it }
        val first = launch { startUpdates() }
        runCurrent()
        val second = launch { assertFailsWith<SwapUpdatesWipeInProgress> { startUpdates() } }
        runCurrent()

        sut.setWiping(true)
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `a start held past the stop timeout fails the stop and a retry stops the stream`() = test {
        val gate = CompletableDeferred<Unit>().also { startGate = it }
        val start = launch { startUpdates() }
        runCurrent()

        assertFailsWith<IllegalStateException> { sut.stopUpdatesAndAwait() }
        assertTrue(calls.isEmpty())
        assertTrue(testScheduler.currentTime >= STOP_TIMEOUT.inWholeMilliseconds)

        gate.complete(Unit)
        start.join()
        sut.stopUpdatesAndAwait()
        advanceUntilIdle()

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `a failed stream stop fails the stop`() = test {
        stopFailure = IllegalStateException("stop failed")

        assertFailsWith<IllegalStateException> { sut.stopUpdatesAndAwait() }
    }

    private suspend fun startUpdates() = sut.startUpdates(
        feeRateSatPerVb = null,
        acceptZeroConf = true,
        network = BoltzNetwork.REGTEST,
    )
}
