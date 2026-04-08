package to.bitkit.services

import com.synonym.bitkitcore.TrezorUiCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.utils.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrezorUiHandler @Inject constructor() : TrezorUiCallback {

    companion object {
        private const val TAG = "TrezorUiHandler"
        private const val UI_TIMEOUT_MS = 120_000L
        private const val PASSPHRASE_CANCEL = "\n"
    }

    private val pinLock = Object()

    @Volatile
    private var pinLatch: CountDownLatch? = null

    @Volatile
    private var submittedPin: String = ""

    private val _needsPinEntry = MutableStateFlow(false)
    val needsPinEntry: StateFlow<Boolean> = _needsPinEntry

    private val passphraseLock = Object()

    @Volatile
    private var passphraseLatch: CountDownLatch? = null

    @Volatile
    private var submittedPassphrase: String = ""

    private val _needsPassphraseEntry = MutableStateFlow(false)
    val needsPassphraseEntry: StateFlow<Boolean> = _needsPassphraseEntry

    override fun onPinRequest(): String {
        Logger.info("PIN requested by device — showing PIN entry dialog", context = TAG)

        val latch = CountDownLatch(1)
        synchronized(pinLock) {
            submittedPin = ""
            pinLatch = latch
            _needsPinEntry.update { true }
        }

        return try {
            val received = latch.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!received) {
                Logger.warn("PIN entry timed out", context = TAG)
                _needsPinEntry.update { false }
                return ""
            }
            val pin = submittedPin
            Logger.info("PIN received (len='${pin.length}')", context = TAG)
            pin
        } catch (e: InterruptedException) {
            Logger.error("PIN wait interrupted", e, context = TAG)
            _needsPinEntry.update { false }
            ""
        }
    }

    override fun onPassphraseRequest(onDevice: Boolean): String {
        if (onDevice) {
            Logger.info("Passphrase requested on-device, acknowledging", context = TAG)
            return "ok"
        }

        Logger.info("Passphrase requested by host — showing passphrase entry dialog", context = TAG)

        val latch = CountDownLatch(1)
        synchronized(passphraseLock) {
            submittedPassphrase = PASSPHRASE_CANCEL
            passphraseLatch = latch
            _needsPassphraseEntry.update { true }
        }

        return try {
            val received = latch.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!received) {
                Logger.warn("Passphrase entry timed out", context = TAG)
                _needsPassphraseEntry.update { false }
                return PASSPHRASE_CANCEL
            }
            val passphrase = submittedPassphrase
            Logger.info("Passphrase received (len='${passphrase.length}')", context = TAG)
            passphrase
        } catch (e: InterruptedException) {
            Logger.error("Passphrase wait interrupted", e, context = TAG)
            _needsPassphraseEntry.update { false }
            PASSPHRASE_CANCEL
        }
    }

    fun submitPin(pin: String) {
        synchronized(pinLock) {
            Logger.info("PIN submitted (len='${pin.length}')", context = TAG)
            submittedPin = pin
            _needsPinEntry.update { false }
            pinLatch?.countDown()
        }
    }

    fun cancelPin() = run { submitPin("") }

    fun submitPassphrase(passphrase: String) {
        synchronized(passphraseLock) {
            Logger.info("Passphrase submitted (len='${passphrase.length}')", context = TAG)
            submittedPassphrase = passphrase
            _needsPassphraseEntry.update { false }
            passphraseLatch?.countDown()
        }
    }

    fun cancelPassphrase() = run { submitPassphrase(PASSPHRASE_CANCEL) }
}
