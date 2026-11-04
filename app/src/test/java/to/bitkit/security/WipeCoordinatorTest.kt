package to.bitkit.security

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.forEachCase
import to.bitkit.usecases.WipeIncomplete
import to.bitkit.usecases.WipeStep
import to.bitkit.usecases.WipeStepFailed
import java.io.IOException
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WipeCoordinatorTest : BaseUnitTest() {
    companion object {
        private const val PENDING_RESET = """{"v":1,"reason":"userReset","startedAt":1,"lastStep":"BEGIN"}"""
        private val PIN_KEY = Keychain.Key.PIN.name
        private val ATTEMPTS_KEY = Keychain.Key.PIN_ATTEMPTS_REMAINING.name
    }

    private val store = InMemoryKeychain()
    private val keychain = store.keychain
    private val walletRepo = mock<WalletRepo>()
    private val context = mock<Context>()

    private lateinit var sut: WipeCoordinator

    private var wipe: suspend () -> Result<Unit> = { completeWipe() }

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        whenever { walletRepo.wipeWallet(any()) }.doSuspendableAnswer { wipe() }
        sut = newProcess()
    }

    private fun completeWipe(): Result<Unit> {
        store.wipePending = null
        return Result.success(Unit)
    }

    private fun newProcess(dispatcher: CoroutineDispatcher = testDispatcher) =
        WipeCoordinator(context, dispatcher, keychain, Provider { walletRepo }, Clock.System)

    private fun advanceBy(seconds: Long) {
        testDispatcher.scheduler.advanceTimeBy(seconds.seconds)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `cancelling the caller scope does not cancel the wipe`() = test {
        val release = CompletableDeferred<Unit>()
        wipe = {
            release.await()
            completeWipe()
        }
        val callerScope = CoroutineScope(testDispatcher + Job())

        callerScope.launch { sut.begin(WipeReason.USER_RESET) }
        callerScope.cancel()
        assertEquals(WipeState.Running, sut.state.value)
        release.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(WipeState.Idle, sut.state.value)
        assertNull(store.wipePending)
        verify(walletRepo).wipeWallet(any())
    }

    @Test
    fun `cancelling the caller while the intent write is in flight still starts the wipe`() = test {
        val writeAcknowledged = CompletableDeferred<Unit>()
        store.beforeWrite = {
            // The write reaches the store before the caller hears back, as a DataStore edit can
            store.wipePending = PENDING_RESET
            writeAcknowledged.await()
        }
        val callerScope = CoroutineScope(testDispatcher + Job())

        callerScope.launch { sut.begin(WipeReason.USER_RESET) }
        callerScope.cancel()
        writeAcknowledged.complete(Unit)
        testScheduler.runCurrent()

        verify(walletRepo).wipeWallet(any())
        assertEquals(WipeState.Idle, sut.state.value)
        assertNull(store.wipePending)
    }

    @Test
    fun `resumes at launch when the flag is present`() = test {
        store.wipePending = PENDING_RESET

        sut.resumeAtLaunch()

        verify(walletRepo).wipeWallet(any())
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `raises the gate at launch before the resumed wipe runs`() = test {
        store.wipePending = PENDING_RESET
        wipe = { awaitCancellation() }

        sut.resumeAtLaunch()

        assertEquals(WipeState.Running, sut.state.value)
    }

    @Test
    fun `an unreadable flag at launch blocks without wiping until a read succeeds`() = test {
        var readError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { readError?.let { throw it } ?: store.wipePending }

        sut.resumeAtLaunch()

        assertIs<WipeState.Blocked>(sut.state.value)
        advanceBy(5)
        assertIs<WipeState.Blocked>(sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
        verify(keychain, never()).saveWipePending(any())

        readError = null
        advanceBy(30)
        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
        verify(walletRepo).setWalletExistsState()
    }

    @Test
    fun `the gate stays blocked while the wallet state cannot be read`() = test {
        var readError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { readError?.let { throw it } ?: store.wipePending }
        var walletReadError: Throwable? = KeychainError.FailedToLoad("BIP39_MNEMONIC")
        whenever(walletRepo.setWalletExistsState()).thenAnswer { walletReadError?.let { throw it } }
        sut.resumeAtLaunch()

        readError = null
        advanceBy(5)
        assertIs<WipeState.Blocked>(sut.state.value)

        walletReadError = null
        advanceBy(30)
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `an unreadable flag that later reads as none still promotes an exhausted counter`() = test {
        store.pin = "1234"
        store.attempts = "0"
        var readError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { readError?.let { throw it } ?: store.wipePending }
        val states = mutableListOf<WipeState>()
        backgroundScope.launch { sut.state.collect { states += it } }
        var statesBeforeWipe = emptyList<WipeState>()
        wipe = {
            statesBeforeWipe = states.toList()
            completeWipe()
        }

        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)

        readError = null
        advanceBy(5)

        verify(walletRepo).wipeWallet(any())
        verify(walletRepo, never()).setWalletExistsState()
        assertTrue(statesBeforeWipe.drop(1).all { it.isGateUp }, statesBeforeWipe.toString())
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `an unreadable PIN attempt counter at launch blocks without wiping until a read succeeds`() = test {
        store.pin = "1234"
        store.attempts = "8"
        var readError: Throwable? = KeychainError.FailedToLoad(ATTEMPTS_KEY)
        whenever(keychain.loadString(ATTEMPTS_KEY)).thenAnswer { readError?.let { throw it } ?: store.attempts }

        sut.resumeAtLaunch()
        advanceBy(10.minutes.inWholeSeconds)

        assertIs<WipeState.Blocked>(sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
        verify(walletRepo, never()).setWalletExistsState()
        verify(keychain, never()).saveWipePending(any())

        readError = null
        sut.retry()
        testScheduler.runCurrent()
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `an unreadable PIN at launch blocks even with attempts left`() = test {
        store.pin = "1234"
        store.attempts = "8"
        whenever(keychain.exists(PIN_KEY)).thenAnswer { throw IOException() }

        sut.resumeAtLaunch()
        advanceBy(5)

        assertIs<WipeState.Blocked>(sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
        verify(walletRepo, never()).setWalletExistsState()
        verify(keychain, never()).saveWipePending(any())
    }

    @Test
    fun `an unreadable PIN attempt counter that later reads as used up runs the forced wipe`() = test {
        store.pin = "1234"
        store.attempts = "0"
        var readError: Throwable? = KeychainError.FailedToLoad(ATTEMPTS_KEY)
        whenever(keychain.loadString(ATTEMPTS_KEY)).thenAnswer { readError?.let { throw it } ?: store.attempts }
        var written: String? = null
        wipe = {
            written = store.wipePending
            completeWipe()
        }

        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)

        readError = null
        advanceBy(5)

        assertTrue(written.orEmpty().contains("pinAttemptsExceeded"), written)
        verify(walletRepo, never()).setWalletExistsState()
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `an unreadable PIN attempt counter that later reads as left lowers the gate to a PIN launch`() = test {
        store.pin = "1234"
        store.attempts = "8"
        var readError: Throwable? = KeychainError.FailedToLoad(ATTEMPTS_KEY)
        whenever(keychain.loadString(ATTEMPTS_KEY)).thenAnswer { readError?.let { throw it } ?: store.attempts }

        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)

        readError = null
        advanceBy(5)

        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo).setWalletExistsState()
        verify(walletRepo, never()).wipeWallet(any())
        assertEquals(PinResult.Correct, PinVerifier(testDispatcher, keychain, sut).verify("1234"))
    }

    @Test
    fun `an unreadable flag that later reads as none stays blocked while the counter cannot be read`() = test {
        store.pin = "1234"
        var flagError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { flagError?.let { throw it } ?: store.wipePending }
        var attemptsError: Throwable? = KeychainError.FailedToLoad(ATTEMPTS_KEY)
        whenever(keychain.loadString(ATTEMPTS_KEY)).thenAnswer { attemptsError?.let { throw it } ?: store.attempts }
        sut.resumeAtLaunch()

        flagError = null
        advanceBy(5)
        assertIs<WipeState.Blocked>(sut.state.value)
        verify(walletRepo, never()).setWalletExistsState()

        attemptsError = null
        advanceBy(30)
        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `retry resumes a pending intent that has no attempt running`() = test {
        store.wipePending = PENDING_RESET

        sut.retry()

        verify(walletRepo).wipeWallet(any())
        assertEquals(WipeState.Idle, sut.state.value)
        assertNull(store.wipePending)
    }

    @Test
    fun `retry raises the blocked gate when the intent cannot be read`() = test {
        whenever(keychain.loadWipePending())
            .thenAnswer { throw KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING) }

        sut.retry()

        assertIs<WipeState.Blocked>(sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `retry without a pending intent leaves the app unlocked`() = test {
        sut.retry()
        testScheduler.runCurrent()

        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
        verify(walletRepo, never()).setWalletExistsState()
    }

    @Test
    fun `an unreadable flag that later reads as pending resumes the wipe`() = test {
        var readError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { readError?.let { throw it } ?: store.wipePending }
        sut.resumeAtLaunch()
        verify(walletRepo, never()).wipeWallet(any())

        readError = null
        store.wipePending = PENDING_RESET
        sut.retry()
        testScheduler.runCurrent()

        verify(walletRepo).wipeWallet(any())
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `a voluntary reset whose flag write fails calls no wipe step and reports the error`() = test {
        store.beforeWrite = { throw IOException() }

        val result = sut.begin(WipeReason.USER_RESET)

        assertTrue(result.isFailure)
        assertEquals(WipeState.Idle, sut.state.value)
        advanceBy(10.minutes.inWholeSeconds)
        verify(keychain).saveWipePending(any())
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `a voluntary reset killed after a confirmed intent resumes on the next process start`() = test {
        store.pin = "1234"
        store.attempts = "8"
        wipe = { awaitCancellation() }

        assertTrue(sut.begin(WipeReason.FORGOT_PIN).isSuccess)
        assertNotNull(store.wipePending)

        wipe = { completeWipe() }
        val nextProcess = newProcess()
        nextProcess.resumeAtLaunch()

        verify(walletRepo, times(2)).wipeWallet(any())
        assertEquals(WipeState.Idle, nextProcess.state.value)
        assertNull(store.wipePending)
    }

    @Test
    fun `a failed wipe stays failed, retries with backoff and never unlocks`() = test {
        val failure = WipeStepFailed(WipeStep.LDK_STORAGE, IOException())
        wipe = { Result.failure(failure) }
        val states = mutableListOf<WipeState>()
        backgroundScope.launch { sut.state.collect { states += it } }

        sut.begin(WipeReason.PIN_ATTEMPTS_EXCEEDED)
        assertEquals(WipeStep.LDK_STORAGE, assertIs<WipeState.Failed>(sut.state.value).step)
        verify(walletRepo, times(1)).wipeWallet(any())

        advanceBy(5)
        verify(walletRepo, times(2)).wipeWallet(any())
        advanceBy(30)
        verify(walletRepo, times(3)).wipeWallet(any())
        advanceBy(2.minutes.inWholeSeconds)
        verify(walletRepo, times(4)).wipeWallet(any())
        advanceBy(1.minutes.inWholeSeconds * 60)
        verify(walletRepo, times(4)).wipeWallet(any())

        sut.retry()
        testScheduler.runCurrent()
        verify(walletRepo, times(5)).wipeWallet(any())
        assertTrue(states.drop(1).all { it.isGateUp })
        assertTrue(store.wipePending.orEmpty().contains("LDK_STORAGE"))
    }

    @Test
    fun `a second begin while a wipe is running joins it`() = test {
        wipe = { awaitCancellation() }

        sut.begin(WipeReason.USER_RESET)
        sut.begin(WipeReason.USER_RESET)

        verify(walletRepo, times(1)).wipeWallet(any())
        verify(keychain, times(1)).saveWipePending(any())
    }

    @Test
    fun `every reason writes and confirms the flag before the first wipe step`() = test {
        val reasons = mapOf(
            WipeReason.PIN_ATTEMPTS_EXCEEDED to "pinAttemptsExceeded",
            WipeReason.USER_RESET to "userReset",
            WipeReason.FORGOT_PIN to "forgotPin",
            WipeReason.RECOVERY_RESET to "recoveryReset",
            WipeReason.DEV to "dev",
        )

        reasons.entries.forEachCase({ it.key.name }) { (reason, value) ->
            var written: String? = null
            wipe = {
                written = store.wipePending
                completeWipe()
            }
            clearInvocations(keychain, walletRepo)
            val coordinator = newProcess()

            assertTrue(coordinator.begin(reason).isSuccess)

            val inOrder = inOrder(keychain, walletRepo)
            inOrder.verify(keychain).saveWipePending(any())
            inOrder.verify(keychain, atLeastOnce()).loadWipePending()
            inOrder.verify(walletRepo).wipeWallet(any())
            assertTrue(written.orEmpty().contains("\"$value\""), written)
        }
    }

    @Test
    fun `an exhausted counter with a PIN present is promoted at launch`() = test {
        store.pin = "1234"
        store.attempts = "0"
        var written: String? = null
        wipe = {
            written = store.wipePending
            completeWipe()
        }

        sut.resumeAtLaunch()

        assertTrue(written.orEmpty().contains("pinAttemptsExceeded"), written)
        verify(walletRepo).wipeWallet(any())
    }

    @Test
    fun `an exhausted counter keeps the gate up from launch until the wipe completes`() = test {
        store.pin = "1234"
        store.attempts = "0"
        val coordinator = newProcess(StandardTestDispatcher(testScheduler))

        coordinator.resumeAtLaunch()

        assertEquals(WipeState.Checking, coordinator.state.value)
        verify(keychain, never()).saveWipePending(any())

        testScheduler.runCurrent()
        verify(walletRepo).wipeWallet(any())
        assertEquals(WipeState.Idle, coordinator.state.value)
    }

    @Test
    fun `an exhausted counter without a PIN is not promoted`() = test {
        store.attempts = "0"

        sut.resumeAtLaunch()

        assertNull(store.wipePending)
        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `an incomplete USDT cleanup still completes the wipe and lowers the gate`() = test {
        wipe = {
            store.wipePending = null
            Result.failure(WipeIncomplete())
        }

        sut.begin(WipeReason.USER_RESET)
        advanceBy(10.minutes.inWholeSeconds)

        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo, times(1)).wipeWallet(any())
    }

    @Test
    fun `launch reads only the wipe intent on the calling thread and checks the PIN attempts behind the gate`() =
        test {
            store.pin = "1234"
            store.attempts = "8"
            val coordinator = newProcess(StandardTestDispatcher(testScheduler))

            coordinator.resumeAtLaunch()

            assertEquals(WipeState.Checking, coordinator.state.value)
            verify(keychain).loadWipePending()
            verify(keychain, never()).exists(any())
            verify(keychain, never()).loadString(any())

            testScheduler.runCurrent()
            assertEquals(WipeState.Idle, coordinator.state.value)
            verify(walletRepo, never()).setWalletExistsState()
        }

    @Test
    fun `retry while unlocked reads the wipe intent off the calling thread`() = test {
        val coordinator = newProcess(StandardTestDispatcher(testScheduler))
        store.wipePending = PENDING_RESET

        coordinator.retry()

        verify(keychain, never()).loadWipePending()
        testScheduler.runCurrent()
        verify(walletRepo).wipeWallet(any())
    }

    @Test
    fun `an unexpected wipe error fails the attempt instead of leaving it running`() = test {
        store.wipePending = PENDING_RESET
        wipe = { throw IllegalStateException("unexpected") }

        sut.resumeAtLaunch()

        assertIs<WipeState.Failed>(sut.state.value)
        wipe = { completeWipe() }
        sut.retry()
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `a timeout inside the wipe fails the attempt instead of ending the retries`() = test {
        store.wipePending = PENDING_RESET
        wipe = { withTimeout(1.seconds) { awaitCancellation() } }

        sut.resumeAtLaunch()
        advanceBy(1)

        assertIs<WipeState.Failed>(sut.state.value)
        wipe = { completeWipe() }
        advanceBy(5)
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `reset from a blocked gate wipes the wallet and lowers the gate only once it completes`() = test {
        store.pin = "1234"
        store.attempts = "8"
        whenever(keychain.exists(PIN_KEY)).thenAnswer { throw IOException() }
        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)
        wipe = { awaitCancellation() }

        sut.reset()

        assertEquals(WipeState.Running, sut.state.value)
        assertTrue(store.wipePending.orEmpty().contains("userReset"))
        verify(walletRepo).wipeWallet(any())
    }

    @Test
    fun `a reset confirmed while an attempt lowers the gate still wipes`() = test {
        var readError: Throwable? = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { readError?.let { throw it } ?: store.wipePending }
        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)
        var intentAtWipe: String? = null
        wipe = {
            intentAtWipe = store.wipePending
            completeWipe()
        }
        // The reset lands while the attempt refreshes the wallet state just before lowering the gate
        whenever(walletRepo.setWalletExistsState()).thenAnswer { sut.reset() }

        readError = null
        advanceBy(5)

        verify(walletRepo).wipeWallet(any())
        assertTrue(intentAtWipe.orEmpty().contains("userReset"))
        assertEquals(WipeState.Idle, sut.state.value)
    }

    @Test
    fun `reset with an unreadable intent tries to write one and fails without wiping`() = test {
        val readError = KeychainError.FailedToLoad(InMemoryKeychain.WIPE_PENDING)
        whenever(keychain.loadWipePending()).thenAnswer { throw readError }
        sut.resumeAtLaunch()
        assertIs<WipeState.Blocked>(sut.state.value)

        sut.reset()

        assertEquals(WipeStep.BEGIN, assertIs<WipeState.Failed>(sut.state.value).step)
        verify(keychain).saveWipePending(any())
        verify(walletRepo, never()).wipeWallet(any())
    }

    @Test
    fun `reset does nothing while the app is unlocked`() = test {
        sut.reset()

        assertEquals(WipeState.Idle, sut.state.value)
        verify(keychain, never()).saveWipePending(any())
    }

    @Test
    fun `an unreadable wallet state raises the gate until it reads again`() = test {
        sut.block(KeychainError.FailedToLoad("BIP39_MNEMONIC"))

        assertIs<WipeState.Blocked>(sut.state.value)
        advanceBy(5)
        assertEquals(WipeState.Idle, sut.state.value)
        verify(walletRepo).setWalletExistsState()
        verify(walletRepo, never()).wipeWallet(any())
    }
}
