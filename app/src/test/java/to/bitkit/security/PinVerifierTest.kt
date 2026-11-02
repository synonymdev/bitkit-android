package to.bitkit.security

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.env.Env
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.WipeStep
import java.io.IOException
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PinVerifierTest : BaseUnitTest() {
    companion object {
        private const val PIN = "1234"
        private const val WRONG_PIN = "0000"
        private val ATTEMPTS_KEY = Keychain.Key.PIN_ATTEMPTS_REMAINING.name
    }

    private val store = InMemoryKeychain()
    private val keychain = store.keychain
    private val walletRepo = mock<WalletRepo>()
    private val context = mock<Context>()

    private lateinit var coordinator: WipeCoordinator
    private lateinit var sut: PinVerifier

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        whenever { walletRepo.wipeWallet(any()) }.thenReturn(Result.success(Unit))
        store.pin = PIN
        coordinator = WipeCoordinator(context, testDispatcher, keychain, Provider { walletRepo }, Clock.System)
        sut = PinVerifier(testDispatcher, keychain, coordinator)
    }

    @Test
    fun `correct PIN under the limit resets the attempts`() = test {
        store.attempts = "3"

        assertEquals(PinResult.Correct, sut.verify(PIN))

        assertEquals("8", store.attempts)
    }

    @Test
    fun `wrong PIN persists the decrement before returning`() = test {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        store.beforeWrite = {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }

        val result = async { sut.verify(WRONG_PIN) }
        writeStarted.await()

        assertFalse(result.isCompleted)
        releaseWrite.complete(Unit)
        assertEquals(PinResult.Incorrect(attemptsRemaining = 7), result.await())
        assertEquals("7", store.attempts)
    }

    @Test
    fun `two concurrent wrong entries decrement twice`() = test {
        store.beforeWrite = { yield() }

        val first = async { sut.verify(WRONG_PIN) }
        val second = async { sut.verify("1111") }

        assertEquals(
            setOf(PinResult.Incorrect(attemptsRemaining = 7), PinResult.Incorrect(attemptsRemaining = 6)),
            setOf(first.await(), second.await()),
        )
        assertEquals("6", store.attempts)
    }

    @Test
    fun `exhaustion writes WIPE_PENDING, reads it back, and only then starts the wipe`() = test {
        store.attempts = "1"

        assertEquals(PinResult.LockedOut, sut.verify(WRONG_PIN))

        assertEquals("0", store.attempts)
        val inOrder = inOrder(keychain, walletRepo)
        inOrder.verify(keychain).upsertString(ATTEMPTS_KEY, "0")
        inOrder.verify(keychain).saveWipePending(any())
        inOrder.verify(keychain, atLeastOnce()).loadWipePending()
        inOrder.verify(walletRepo).wipeWallet(any())
        assertTrue(store.wipePending.orEmpty().contains("pinAttemptsExceeded"))
    }

    @Test
    fun `exhaustion with a failing flag write returns LockedOut, starts no wipe step and retries the write`() =
        test {
            store.attempts = "1"
            var isFlagWriteFailing = true
            store.beforeWrite = { if (it == InMemoryKeychain.WIPE_PENDING && isFlagWriteFailing) throw IOException() }

            assertEquals(PinResult.LockedOut, sut.verify(WRONG_PIN))

            assertEquals(WipeStep.BEGIN, assertIs<WipeState.Failed>(coordinator.state.value).step)
            assertFalse(sut.canUseBiometrics.first())
            assertEquals(PinResult.LockedOut, sut.verify(PIN))
            verify(walletRepo, never()).wipeWallet(any())
            verify(keychain).saveWipePending(any())

            testScheduler.advanceTimeBy(5.seconds)
            testScheduler.runCurrent()
            verify(keychain, times(2)).saveWipePending(any())
            verify(walletRepo, never()).wipeWallet(any())

            isFlagWriteFailing = false
            testScheduler.advanceTimeBy(30.seconds)
            testScheduler.runCurrent()
            verify(walletRepo).wipeWallet(any())
        }

    @Test
    fun `correct PIN at zero attempts returns LockedOut and does not reset`() = test {
        store.attempts = "0"

        assertEquals(PinResult.LockedOut, sut.verify(PIN))

        assertEquals("0", store.attempts)
        verify(keychain, never()).upsertString(any(), any())
    }

    @Test
    fun `correct PIN while a wipe is pending returns LockedOut and does not reset`() = test {
        store.attempts = "5"
        store.wipePending = "{}"

        assertEquals(PinResult.LockedOut, sut.verify(PIN))

        assertEquals("5", store.attempts)
        verify(keychain, never()).upsertString(any(), any())
    }

    @Test
    fun `correct PIN while the wipe flag is unreadable returns LockedOut`() = test {
        whenever(keychain.loadWipePending()).thenAnswer { throw KeychainError.FailedToLoad("flag") }

        assertEquals(PinResult.LockedOut, sut.verify(PIN))

        verify(keychain, never()).upsertString(any(), any())
        verify(keychain, never()).saveWipePending(any())
    }

    @Test
    fun `biometrics are disallowed when attempts are exhausted or the gate is up`() = test {
        store.attempts = "3"
        assertTrue(sut.canUseBiometrics.first())

        store.attempts = "0"
        assertFalse(sut.canUseBiometrics.first())

        store.attempts = "3"
        coordinator.block(KeychainError.FailedToLoad("wallet"))
        assertFalse(sut.canUseBiometrics.first())
    }

    @Test
    fun `biometrics are disallowed while the attempts cannot be read`() = test {
        whenever(keychain.pinAttemptsRemaining()).thenReturn(flow { throw IOException() })
        sut = PinVerifier(testDispatcher, keychain, coordinator)

        assertFalse(sut.canUseBiometrics.first())
    }

    @Test
    fun `legacy exhausted counter with a PIN present is promoted to a pending wipe`() = test {
        store.attempts = "0"

        assertEquals(PinResult.LockedOut, sut.verify(PIN))

        assertNotNull(store.wipePending)
        verify(walletRepo).wipeWallet(any())
    }

    @Test
    fun `unreadable PIN returns Unavailable without counting the attempt`() = test {
        store.attempts = "4"
        whenever(keychain.loadString(Keychain.Key.PIN.name)).thenAnswer { throw KeychainError.FailedToLoad("pin") }

        assertEquals(PinResult.Unavailable, sut.verify(PIN))

        assertEquals("4", store.attempts)
        assertFalse(coordinator.state.value.isGateUp)
    }

    @Test
    fun `a PIN that can never be read does not lead to a wipe however often it is entered`() = test {
        whenever(keychain.loadString(Keychain.Key.PIN.name)).thenAnswer { throw KeychainError.FailedToLoad("pin") }

        repeat(Env.PIN_ATTEMPTS + 1) { assertEquals(PinResult.Unavailable, sut.verify(PIN)) }

        assertNull(store.attempts)
        assertNull(store.wipePending)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `a failed attempt write returns Unavailable even for the correct PIN`() = test {
        store.attempts = "4"
        store.beforeWrite = { throw IOException() }

        assertEquals(PinResult.Unavailable, sut.verify(PIN))

        assertEquals("4", store.attempts)
        assertFalse(coordinator.state.value.isGateUp)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `entries are checked again once attempt writes succeed`() = test {
        var isWriteFailing = true
        store.beforeWrite = { if (isWriteFailing) throw IOException() }
        assertEquals(PinResult.Unavailable, sut.verify(WRONG_PIN))

        isWriteFailing = false

        assertEquals(PinResult.Incorrect(attemptsRemaining = 7), sut.verify(WRONG_PIN))
        assertEquals(PinResult.Correct, sut.verify(PIN))
        assertEquals("8", store.attempts)
    }

    @Test
    fun `an entry without a stored PIN neither counts nor starts a wipe`() = test {
        store.pin = null
        store.attempts = "1"

        assertEquals(PinResult.Unavailable, sut.verify(WRONG_PIN))
        store.attempts = "0"
        assertEquals(PinResult.Unavailable, sut.verify(WRONG_PIN))

        assertEquals("0", store.attempts)
        assertNull(store.wipePending)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `an unreadable wipe intent raises the gate that explains the refusal`() = test {
        whenever(keychain.loadWipePending()).thenAnswer { throw KeychainError.FailedToLoad("flag") }

        assertEquals(PinResult.LockedOut, sut.verify(PIN))
        testScheduler.runCurrent()

        assertIs<WipeState.Blocked>(coordinator.state.value)
    }
}
