package to.bitkit.ui.shared.effects

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ext.bluetoothManager
import to.bitkit.nearby.NearbyAdvertiser
import to.bitkit.nearby.NearbyScanner
import to.bitkit.nfc.nfcPaymentUri
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.LocalIsAppLocked
import to.bitkit.ui.appViewModel
import to.bitkit.ui.settingsViewModel

/**
 * Permissions requested from the scanner's Bluetooth button: the Android 12+ "Nearby devices" group
 * (all three, so Receive can advertise afterwards), or location before Android 12, which scanning needs.
 */
val nearbyPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private val scanPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Advertising and the GATT server need no runtime permission before Android 12. */
private val advertisePermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    emptyArray()
}

enum class NearbyRole { SCAN, ADVERTISE }

fun Context.bluetoothState(role: NearbyRole): BluetoothState {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BluetoothState.UNAVAILABLE
    val adapter = bluetoothManager.adapter ?: return BluetoothState.UNAVAILABLE
    val permissions = if (role == NearbyRole.SCAN) scanPermissions else advertisePermissions
    val granted = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    return when {
        !granted -> BluetoothState.NEEDS_PERMISSION
        !adapter.isEnabled -> BluetoothState.OFF
        role == NearbyRole.SCAN && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled() ->
            BluetoothState.LOCATION_OFF

        // Some phones can scan but not advertise; Receive must not claim it can be found over Bluetooth there.
        role == NearbyRole.ADVERTISE && !adapter.isMultipleAdvertisementSupported -> BluetoothState.UNAVAILABLE

        else -> BluetoothState.ON
    }
}

/** Before Android 12, BLE scans return nothing while location services are off. */
private fun Context.isLocationEnabled(): Boolean {
    val locationManager = getSystemService(LocationManager::class.java) ?: return false
    return LocationManagerCompat.isLocationEnabled(locationManager)
}

/**
 * Bluetooth availability for nearby payments in [role]. Refreshed when the screen resumes, when
 * [refreshKey] changes (after a permission result) and when Bluetooth or location is switched.
 */
@Composable
fun rememberBluetoothState(role: NearbyRole, refreshKey: Any? = null): BluetoothState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(role, refreshKey) { mutableStateOf(context.bluetoothState(role)) }
    DisposableEffect(context, lifecycleOwner, role, refreshKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = context.bluetoothState(role)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                state = context.bluetoothState(role)
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return state
}

/**
 * Reads payment requests from nearby Bitkit wallets while the calling screen is resumed.
 * Pass [enabled] only while [rememberBluetoothState] reports [BluetoothState.ON] for [NearbyRole.SCAN];
 * the effect restarts whenever it changes, so an adapter that was switched off and on is picked up again.
 */
@Composable
fun NearbyScanEffect(enabled: Boolean, onRead: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRead by rememberUpdatedState(onRead)

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val scanner = NearbyScanner(context.applicationContext) { currentOnRead(it) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startScanner(scanner)
                Lifecycle.Event.ON_PAUSE -> scanner.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startScanner(scanner)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scanner.stop()
        }
    }
}

@SuppressLint("MissingPermission") // NearbyScanEffect runs only while the scan permissions are granted.
private fun startScanner(scanner: NearbyScanner) = scanner.start()

/**
 * Serves [uri] to nearby Bitkit wallets over Bluetooth while the calling screen is resumed.
 * [onFailed] runs when advertising can't start, so the screen stops saying it can be tapped.
 */
@Composable
fun NearbyAdvertiseEffect(uri: String?, onFailed: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFailed by rememberUpdatedState(onFailed)

    DisposableEffect(context, lifecycleOwner, uri) {
        if (uri.isNullOrBlank()) return@DisposableEffect onDispose {}
        val advertiser = NearbyAdvertiser(context.applicationContext) { currentOnFailed() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startAdvertiser(advertiser, uri)
                Lifecycle.Event.ON_PAUSE -> stopAdvertiser(advertiser)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startAdvertiser(advertiser, uri)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopAdvertiser(advertiser)
        }
    }
}

@SuppressLint("MissingPermission") // NearbyAdvertiseEffect gets a uri only while the advertise permissions are granted.
private fun startAdvertiser(advertiser: NearbyAdvertiser, uri: String) = advertiser.start(uri)

@SuppressLint("MissingPermission") // Same as startAdvertiser.
private fun stopAdvertiser(advertiser: NearbyAdvertiser) = advertiser.stop()

/**
 * Whether nearby payments pause: while the PIN screen covers the app, or while the connection issues screen
 * covers the payment sheets, so nothing is offered or read that the user cannot see.
 */
@Composable
fun isNearbyPaused(): Boolean {
    val isLocked = LocalIsAppLocked.current
    val connectivity = appViewModel?.isOnline?.collectAsStateWithLifecycle()?.value ?: ConnectivityState.CONNECTED
    return isLocked || connectivity != ConnectivityState.CONNECTED
}

/** The URI offered to nearby payers for [request], or null while nearby payments are paused. */
internal fun nearbyOfferUri(request: String?, isPaused: Boolean): String? =
    nfcPaymentUri(request)?.takeUnless { isPaused }

/**
 * Offers the Receive request to nearby payers while the calling screen is resumed and nearby payments
 * are not paused: over NFC when the device can emulate a tag, and over Bluetooth when it is on and the
 * nearby setting allows it. Returns whether either is active, which is when Receive says it can be tapped.
 * NFC stops counting as active once its tag service fails to register, and Bluetooth once its advertising
 * fails to start.
 */
@Composable
fun offerPaymentNearby(request: String?): Boolean {
    val uri = nearbyOfferUri(request, isPaused = isNearbyPaused())
    val canEmitNfc = rememberCanEmitNfc()
    // A failed registration is retried when the request or the NFC state changes.
    var emitFailed by remember(uri, canEmitNfc) { mutableStateOf(false) }
    val emitsNfc = canEmitNfc && !emitFailed
    val bluetoothState = rememberBluetoothState(NearbyRole.ADVERTISE)
    val isNearbyEnabled by settingsViewModel?.isNearbyPaymentsEnabled?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    // A failed start is retried when the request or the Bluetooth state changes.
    var advertiseFailed by remember(uri, bluetoothState) { mutableStateOf(false) }
    val canAdvertise = isNearbyEnabled && bluetoothState == BluetoothState.ON && !advertiseFailed

    NfcEmitterEffect(uri = uri.takeIf { emitsNfc }, onFailed = { emitFailed = true })
    NearbyAdvertiseEffect(uri = uri.takeIf { canAdvertise }, onFailed = { advertiseFailed = true })
    return uri != null && (emitsNfc || canAdvertise)
}
