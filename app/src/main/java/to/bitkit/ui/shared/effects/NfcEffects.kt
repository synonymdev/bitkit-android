package to.bitkit.ui.shared.effects

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.Ndef
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import to.bitkit.androidServices.NdefTagService
import to.bitkit.nfc.NdefCodec
import to.bitkit.nfc.NfcTagEmitter
import to.bitkit.utils.Logger

private const val TAG = "NfcEffects"
private const val DUPLICATE_READ_WINDOW_MS = 3_000L
private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V

enum class NfcState { UNAVAILABLE, OFF, ON }

fun Context.nfcState(): NfcState {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return NfcState.UNAVAILABLE
    return if (adapter.isEnabled) NfcState.ON else NfcState.OFF
}

fun Context.supportsNfcCardEmulation(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

fun Context.openNfcSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        .onFailure { Logger.warn("Failed to open NFC settings", it, context = TAG) }
}

/** Whether this device can offer a payment request to an NFC reader right now. */
@Composable
fun rememberCanEmitNfc(): Boolean {
    val context = LocalContext.current
    val nfcState = rememberNfcState()
    val supportsCardEmulation = remember(context) { context.supportsNfcCardEmulation() }
    return nfcState == NfcState.ON && supportsCardEmulation
}

/** NFC availability, refreshed every time the screen resumes (e.g. after the user turns NFC on). */
@Composable
fun rememberNfcState(): NfcState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(context.nfcState()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = context.nfcState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * Reads NDEF tags while the calling screen is resumed and hands over the payment request
 * found on the tag. Reader mode keeps the system from opening another app for the tag and
 * pauses this phone's own card emulation, so it is only active on scanning screens.
 */
@Composable
fun NfcReaderEffect(
    onRead: (String) -> Unit,
    onUnreadable: () -> Unit,
    enabled: Boolean = true,
) {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRead by rememberUpdatedState(onRead)
    val currentOnUnreadable by rememberUpdatedState(onUnreadable)

    DisposableEffect(activity, lifecycleOwner, enabled) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (!enabled || adapter == null) return@DisposableEffect onDispose {}

        val mainHandler = Handler(Looper.getMainLooper())
        var lastRead: Pair<String, Long>? = null
        val callback = NfcAdapter.ReaderCallback { tag ->
            val text = readPaymentText(tag)
            mainHandler.post {
                if (text == null) {
                    currentOnUnreadable()
                    return@post
                }
                val now = System.currentTimeMillis()
                val previous = lastRead
                if (previous != null && previous.first == text && now - previous.second < DUPLICATE_READ_WINDOW_MS) {
                    return@post
                }
                lastRead = text to now
                currentOnRead(text)
            }
        }

        fun enable() = runCatching { adapter.enableReaderMode(activity, callback, READER_FLAGS, null) }
            .onFailure { Logger.warn("Failed to enable NFC reader mode", it, context = TAG) }

        fun disable() = runCatching { adapter.disableReaderMode(activity) }
            .onFailure { Logger.warn("Failed to disable NFC reader mode", it, context = TAG) }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> enable()
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enable()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            disable()
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}

private fun readPaymentText(tag: Tag): String? {
    val ndef = Ndef.get(tag) ?: return null
    val message = runCatching {
        ndef.cachedNdefMessage ?: ndef.use {
            it.connect()
            it.ndefMessage
        }
    }.onFailure {
        Logger.warn("Failed to read NFC tag", it, context = TAG)
    }.getOrNull() ?: return null
    return NdefCodec.payableText(NdefCodec.decodeTexts(message.toByteArray()))
}

/**
 * Offers [uri] to NFC readers while the calling screen is resumed, through [NdefTagService].
 * The service is made the preferred one for the NDEF tag AID meanwhile, so another app that
 * registers the same AID does not answer instead.
 */
@Composable
fun NfcEmitterEffect(uri: String?) {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(activity, lifecycleOwner, uri) {
        val cardEmulation = cardEmulation(activity)
        if (uri.isNullOrBlank() || cardEmulation == null) return@DisposableEffect onDispose {}
        val service = ComponentName(activity, NdefTagService::class.java)

        fun start() {
            NfcTagEmitter.publish(uri)
            runCatching { cardEmulation.setPreferredService(activity, service) }
                .onFailure { Logger.warn("Failed to prefer NFC tag service", it, context = TAG) }
        }

        fun stop() {
            NfcTagEmitter.clear()
            runCatching { cardEmulation.unsetPreferredService(activity) }
                .onFailure { Logger.warn("Failed to release NFC tag service", it, context = TAG) }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
}

private fun cardEmulation(activity: Activity): CardEmulation? {
    if (!activity.supportsNfcCardEmulation()) return null
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
    return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
}
