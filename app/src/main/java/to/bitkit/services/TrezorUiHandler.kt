package to.bitkit.services

import com.synonym.bitkitcore.PassphraseResponse
import com.synonym.bitkitcore.TrezorUiCallback
import com.synonym.bitkitcore.WalletSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.utils.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which wallet to open when the device asks for a passphrase.
 *
 * - [STANDARD]: no passphrase — the default wallet.
 * - [PASSPHRASE_HOST]: a hidden wallet, passphrase typed on the phone.
 * - [PASSPHRASE_DEVICE]: a hidden wallet, passphrase typed on the Trezor.
 */
enum class TrezorWalletMode { STANDARD, PASSPHRASE_HOST, PASSPHRASE_DEVICE }

@Singleton
class TrezorUiHandler @Inject constructor() : TrezorUiCallback {

    companion object {
        private const val TAG = "TrezorUiHandler"
        private const val UI_TIMEOUT_MS = 120_000L
    }

    private val pinLock = Object()

    @Volatile
    private var pinLatch: CountDownLatch? = null

    @Volatile
    private var submittedPin: String = ""

    private val _needsPinEntry = MutableStateFlow(false)
    val needsPinEntry: StateFlow<Boolean> = _needsPinEntry

    private val _walletMode = MutableStateFlow(TrezorWalletMode.STANDARD)
    val walletMode: StateFlow<TrezorWalletMode> = _walletMode

    /**
     * Host passphrase captured when [TrezorWalletMode.PASSPHRASE_HOST] is
     * selected. Mirrors the value bound to the THP session so legacy (non-THP)
     * devices — which re-request the passphrase mid-operation via
     * [onPassphraseRequest] — can be answered from the value the user already
     * entered up front. Null when not in host-passphrase mode.
     */
    @Volatile
    private var hostPassphrase: String? = null

    /**
     * Set which wallet to open. The caller is responsible for resetting the
     * device session (disconnect/reconnect) so the new mode takes effect — the
     * Trezor caches the passphrase for the lifetime of a session.
     *
     * [hostPassphrase] is only meaningful for [TrezorWalletMode.PASSPHRASE_HOST]
     * — it is the passphrase the user entered on the phone up front.
     */
    fun setWalletMode(mode: TrezorWalletMode, hostPassphrase: String = "") {
        Logger.info("Wallet mode set to $mode", context = TAG)
        this.hostPassphrase = if (mode == TrezorWalletMode.PASSPHRASE_HOST) hostPassphrase else null
        _walletMode.update { mode }
    }

    /**
     * The wallet the current mode/passphrase selects, for binding to a THP
     * session when [connect] runs. Mirrors [onPassphraseRequest] so THP (bound
     * at session creation) and legacy devices (answered mid-operation) stay in
     * lockstep from one source of truth. Reconnects derive their wallet from
     * here, so it reflects the selection until the next [setWalletMode] or
     * disconnect.
     */
    fun currentSelection(): WalletSelection = when (_walletMode.value) {
        TrezorWalletMode.STANDARD -> WalletSelection.Standard
        TrezorWalletMode.PASSPHRASE_DEVICE -> WalletSelection.OnDevice
        TrezorWalletMode.PASSPHRASE_HOST -> {
            val cached = hostPassphrase
            if (cached.isNullOrEmpty()) {
                WalletSelection.Standard
            } else {
                WalletSelection.Hidden(passphrase = cached)
            }
        }
    }

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

    override fun onPassphraseRequest(onDevice: Boolean): PassphraseResponse {
        // Device-mandated on-device entry always wins, regardless of mode.
        if (onDevice) {
            Logger.info("Passphrase requested on-device (device-mandated), deferring to Trezor", context = TAG)
            return PassphraseResponse.OnDevice
        }

        return when (_walletMode.value) {
            TrezorWalletMode.STANDARD -> {
                Logger.info("Standard wallet — answering passphrase request with Standard", context = TAG)
                PassphraseResponse.Standard
            }
            TrezorWalletMode.PASSPHRASE_DEVICE -> {
                Logger.info("Passphrase wallet (on-device entry) — deferring to Trezor", context = TAG)
                PassphraseResponse.OnDevice
            }
            TrezorWalletMode.PASSPHRASE_HOST -> {
                // Answer from the passphrase entered up front (the same value
                // bound to the THP session). Empty/absent == the standard wallet.
                val cached = hostPassphrase
                if (cached.isNullOrEmpty()) {
                    PassphraseResponse.Standard
                } else {
                    Logger.info("Host passphrase wallet — answering with the pre-entered passphrase", context = TAG)
                    PassphraseResponse.Hidden(value = cached)
                }
            }
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
}
