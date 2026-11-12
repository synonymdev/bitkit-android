package to.bitkit.ui.shared.effects

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.nearby.NearbyAdvertiser
import to.bitkit.nearby.NearbyScanner
import to.bitkit.nfc.nfcPaymentUri
import to.bitkit.ui.settingsViewModel

/** Runtime permissions nearby payments need: the Android 12+ "Nearby devices" group, or location before it. */
val nearbyPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun Context.bluetoothState(): BluetoothState {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BluetoothState.UNAVAILABLE
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return BluetoothState.UNAVAILABLE
    val granted = nearbyPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    return when {
        !granted -> BluetoothState.NEEDS_PERMISSION
        !adapter.isEnabled -> BluetoothState.OFF
        else -> BluetoothState.ON
    }
}

/** Bluetooth availability for nearby payments, refreshed every time the screen resumes. */
@Composable
fun rememberBluetoothState(refreshKey: Any? = null): BluetoothState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(refreshKey) { mutableStateOf(context.bluetoothState()) }
    DisposableEffect(lifecycleOwner, refreshKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = context.bluetoothState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Reads payment requests from nearby Bitkit wallets while the calling screen is resumed. */
@SuppressLint("MissingPermission") // Callers pass enabled only when BluetoothState is ON.
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
                Lifecycle.Event.ON_RESUME -> scanner.start()
                Lifecycle.Event.ON_PAUSE -> scanner.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scanner.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scanner.stop()
        }
    }
}

/** Serves [uri] to nearby Bitkit wallets over Bluetooth while the calling screen is resumed. */
@SuppressLint("MissingPermission") // Callers pass a uri only when BluetoothState is ON.
@Composable
fun NearbyAdvertiseEffect(uri: String?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(context, lifecycleOwner, uri) {
        if (uri.isNullOrBlank()) return@DisposableEffect onDispose {}
        val advertiser = NearbyAdvertiser(context.applicationContext)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> advertiser.start(uri)
                Lifecycle.Event.ON_PAUSE -> advertiser.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) advertiser.start(uri)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            advertiser.stop()
        }
    }
}

/**
 * Offers the Receive request to nearby payers while the calling screen is resumed: over NFC when
 * the device can emulate a tag, and over Bluetooth when it is on and the nearby setting allows it.
 * Returns whether either is active, which is when the Receive screen says it can be tapped.
 */
@Composable
fun offerPaymentNearby(request: String?): Boolean {
    val uri = nfcPaymentUri(request)
    val canEmitNfc = rememberCanEmitNfc()
    val bluetoothState = rememberBluetoothState()
    val isNearbyEnabled by settingsViewModel?.isNearbyPaymentsEnabled?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    val canAdvertise = isNearbyEnabled && bluetoothState == BluetoothState.ON

    NfcEmitterEffect(uri = uri.takeIf { canEmitNfc })
    NearbyAdvertiseEffect(uri = uri.takeIf { canAdvertise })
    return uri != null && (canEmitNfc || canAdvertise)
}
