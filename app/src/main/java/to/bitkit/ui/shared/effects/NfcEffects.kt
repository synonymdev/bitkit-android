package to.bitkit.ui.shared.effects

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.bitkit.androidServices.NdefTagService
import to.bitkit.ext.cameraManager
import to.bitkit.nfc.CameraNfcStopTracker
import to.bitkit.nfc.NdefCodec
import to.bitkit.nfc.NfcDevOverride
import to.bitkit.nfc.NfcReadDispatcher
import to.bitkit.nfc.NfcRepeatGuard
import to.bitkit.nfc.NfcTagEmitter
import to.bitkit.nfc.READER_FLAGS
import to.bitkit.nfc.ReaderModeOwner
import to.bitkit.nfc.Type4TagEmulator
import to.bitkit.nfc.deviceCameraStopsNfcPolling
import to.bitkit.nfc.nfcPaymentRequest
import to.bitkit.utils.Logger

private const val TAG = "NfcEffects"
private val NDEF_AIDS = listOf(Type4TagEmulator.NDEF_AID_HEX)

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

/** NFC availability, refreshed when the screen resumes and whenever the adapter turns on or off. */
@Composable
fun rememberNfcState(): NfcState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(context.nfcState()) }
    val override by NfcDevOverride.adapterState.collectAsStateWithLifecycle()
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = context.nfcState()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                state = context.nfcState()
            }
        }
        // The NFC service, not the system server, sends this protected broadcast, so the receiver must be exported.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return override ?: state
}

/**
 * Whether NFC polling may be off because a camera is open, on phones that switch it off then (see
 * [to.bitkit.nfc.NOTIFY_NFC_PROPERTY] and [CameraNfcStopTracker]). Errs toward true: a stop the system no longer
 * applies after an NFC toggle still counts until every camera is available again.
 */
@Composable
fun rememberNfcStoppedByCamera(nfcState: NfcState): Boolean {
    val override by NfcDevOverride.cameraStopsPolling.collectAsStateWithLifecycle()
    val deviceStopsPolling by produceState(false) {
        value = withContext(Dispatchers.IO) { deviceCameraStopsNfcPolling() }
    }
    val cameraStopsPolling = override ?: deviceStopsPolling
    val isEnabled = nfcState == NfcState.ON && cameraStopsPolling
    val isStopped = rememberCameraNfcStop(enabled = isEnabled) && isEnabled
    LaunchedEffect(isStopped) {
        if (isStopped) Logger.info("Detected NFC polling stopped by an open camera", context = TAG)
    }
    return isStopped
}

@Composable
private fun rememberCameraNfcStop(enabled: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isStopped by remember { mutableStateOf(false) }

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val tracker = CameraNfcStopTracker()
        val cameraManager = context.cameraManager
        fun presentCameras() = runCatching { cameraManager.cameraIdList.toSet() }.getOrDefault(emptySet())

        // Registered on the main looper, so the tracker is only touched from the main thread.
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                tracker.onCameraAvailable(cameraId)
                isStopped = tracker.isStopped
            }

            override fun onCameraUnavailable(cameraId: String) {
                tracker.onCameraUnavailable(cameraId, presentCameras())
                isStopped = tracker.isStopped
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            tracker.retainPresent(presentCameras())
            isStopped = tracker.isStopped
        }
        val registered = runCatching {
            cameraManager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure { Logger.warn("Failed to watch camera availability", it, context = TAG) }.isSuccess
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) cameraManager.unregisterAvailabilityCallback(callback)
            isStopped = false
        }
    }
    return isStopped
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
        val dispatcher = NfcReadDispatcher(onRead = { currentOnRead(it) }, onUnreadable = { currentOnUnreadable() })

        // Android may call a callback after reader mode is disabled, so each activation gets its own callback.
        fun callback(activation: Int) = NfcAdapter.ReaderCallback { tag ->
            val text = readPaymentText(tag)
            mainHandler.post {
                // The tag stays in range after the read; hold reader mode so Android does not deliver it again.
                // A read from a closed activation leaves reader mode to whichever scanner owns it now.
                if (text != null && dispatcher.isCurrent(activation)) NfcRepeatGuard.engage(activity, tag)
                dispatcher.deliver(text, activation)
            }
        }

        val owner = Any()

        fun enable() = runCatching {
            adapter.enableReaderMode(activity, callback(dispatcher.open()), READER_FLAGS, null)
            ReaderModeOwner.current = owner
        }.onFailure { Logger.warn("Failed to enable NFC reader mode", it, context = TAG) }

        val resume: () -> Unit = { enable() }

        fun disable() {
            // A read still in progress, or a callback Android calls after this, posts a closed activation.
            dispatcher.close()
            if (ReaderModeOwner.resumeScanner === resume) ReaderModeOwner.resumeScanner = null
            if (ReaderModeOwner.current !== owner) return
            ReaderModeOwner.current = null
            runCatching { adapter.disableReaderMode(activity) }
                .onFailure { Logger.warn("Failed to disable NFC reader mode", it, context = TAG) }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ReaderModeOwner.resumeScanner = resume
                    enable()
                }
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ReaderModeOwner.resumeScanner = resume
            enable()
        }

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
    return NdefCodec.payableText(NdefCodec.decodeTexts(message.toByteArray()))?.let(::nfcPaymentRequest)
}

/**
 * Offers [uri] to NFC readers while the calling screen is resumed, through [NdefTagService].
 * The NDEF tag AID is registered for the service only meanwhile, and the service is made the
 * preferred one, so Bitkit does not answer readers elsewhere and another app with the same AID
 * does not answer instead.
 */
@Composable
fun NfcEmitterEffect(uri: String?, onFailed: () -> Unit = {}) {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFailed by rememberUpdatedState(onFailed)

    DisposableEffect(activity, lifecycleOwner, uri) {
        val cardEmulation = cardEmulation(activity)
        if (uri.isNullOrBlank() || cardEmulation == null) return@DisposableEffect onDispose {}
        val service = ComponentName(activity, NdefTagService::class.java)

        var token: Long? = null

        fun start() {
            val published = NfcTagEmitter.publish(uri)
            token = published
            if (registerTagService(cardEmulation, activity, service)) return
            // Without the AID no reader reaches the service, so Receive must not say it can be tapped.
            token = null
            NfcTagEmitter.clear(published)
            currentOnFailed()
        }

        fun stop() {
            val published = token ?: return
            token = null
            // Another screen may have published since; it then owns the service registration too.
            if (!NfcTagEmitter.clear(published)) return
            runCatching {
                cardEmulation.unsetPreferredService(activity)
                cardEmulation.removeAidsForService(service, CardEmulation.CATEGORY_OTHER)
            }.onFailure { Logger.warn("Failed to release NFC tag service", it, context = TAG) }
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

/**
 * Routes the NDEF tag AID to [service] and prefers it. Returns whether both succeeded: without the preference,
 * another service holding the same AID can answer the tap, so the AID is released again.
 */
internal fun registerTagService(cardEmulation: CardEmulation, activity: Activity, service: ComponentName): Boolean =
    runCatching {
        val isRegistered = cardEmulation.registerAidsForService(service, CardEmulation.CATEGORY_OTHER, NDEF_AIDS)
        if (!isRegistered) return@runCatching false
        if (cardEmulation.setPreferredService(activity, service)) return@runCatching true
        cardEmulation.removeAidsForService(service, CardEmulation.CATEGORY_OTHER)
        false
    }.onFailure { Logger.warn("Failed to register NFC tag service", it, context = TAG) }
        .onSuccess { if (!it) Logger.warn("NFC tag service registration was refused", context = TAG) }
        .getOrDefault(false)

private fun cardEmulation(activity: Activity): CardEmulation? {
    if (!activity.supportsNfcCardEmulation()) return null
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
    return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
}
