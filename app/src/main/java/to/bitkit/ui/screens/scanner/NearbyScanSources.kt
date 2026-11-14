package to.bitkit.ui.screens.scanner

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.LocalIsAppLocked
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.shared.effects.BluetoothState
import to.bitkit.ui.shared.effects.NearbyRole
import to.bitkit.ui.shared.effects.NearbyScanEffect
import to.bitkit.ui.shared.effects.NfcReaderEffect
import to.bitkit.ui.shared.effects.NfcState
import to.bitkit.ui.shared.effects.nearbyPermissions
import to.bitkit.ui.shared.effects.openNfcSettings
import to.bitkit.ui.shared.effects.rememberBluetoothState
import to.bitkit.ui.shared.effects.rememberNfcState
import to.bitkit.ui.theme.Colors

/** NFC and Bluetooth availability on a payment scanner, with the actions that turn each one on. */
@Stable
data class NearbyScanSources(
    val nfcState: NfcState,
    val bluetoothState: BluetoothState,
    val onEnableNfc: () -> Unit,
    val onEnableBluetooth: () -> Unit,
) {
    val isNfcActive get() = nfcState == NfcState.ON
    val isBluetoothActive get() = bluetoothState == BluetoothState.ON
    val canEnableNfc get() = nfcState == NfcState.OFF
    val canEnableBluetooth
        get() = bluetoothState in ENABLEABLE_BLUETOOTH_STATES

    companion object {
        private val ENABLEABLE_BLUETOOTH_STATES =
            setOf(BluetoothState.NEEDS_PERMISSION, BluetoothState.OFF, BluetoothState.LOCATION_OFF)

        val None = NearbyScanSources(NfcState.UNAVAILABLE, BluetoothState.UNAVAILABLE, {}, {})
    }
}

/**
 * Listens for payment requests over NFC and Bluetooth while the scanner is resumed and the app is
 * unlocked. [onRead] receives the request and whether it came over Bluetooth.
 */
@Composable
fun rememberNearbyScanSources(
    enabled: Boolean,
    onRead: (text: String, isNearby: Boolean) -> Unit,
): NearbyScanSources {
    if (!enabled) return NearbyScanSources.None
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = appViewModel
    val isLocked = LocalIsAppLocked.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val nfcState = rememberNfcState()
    val bluetoothState = rememberBluetoothState(NearbyRole.SCAN, refreshKey)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshKey++ }

    if (nfcState == NfcState.ON && !isLocked) {
        NfcReaderEffect(
            onRead = { onRead(it, false) },
            onUnreadable = {
                app?.toast(
                    type = Toast.ToastType.ERROR,
                    title = resources.getString(R.string.other__nfc_read_error_title),
                    description = resources.getString(R.string.other__nfc_read_error_text),
                )
            },
        )
    }
    NearbyScanEffect(enabled = bluetoothState == BluetoothState.ON && !isLocked, onRead = { onRead(it, true) })

    return NearbyScanSources(
        nfcState = nfcState,
        bluetoothState = bluetoothState,
        onEnableNfc = { context.openNfcSettings() },
        onEnableBluetooth = {
            when (bluetoothState) {
                BluetoothState.NEEDS_PERMISSION -> permissionLauncher.launch(nearbyPermissions)
                BluetoothState.OFF -> runCatching {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }

                BluetoothState.LOCATION_OFF -> runCatching {
                    enableBluetoothLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }

                else -> Unit
            }
        },
    )
}

/** Enable buttons for sources that are off, then the status pill naming what is being scanned. */
@Composable
fun NearbyScanStatus(
    sources: NearbyScanSources,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        NearbyEnableButtons(sources)
        ScannerStatusPill(text = stringResource(scannerStatusText(sources)))
    }
}

/** Icon buttons that turn on Bluetooth (permission or adapter) and NFC; nothing when both are on. */
@Composable
fun NearbyEnableButtons(
    sources: NearbyScanSources,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    if (leading == null && !sources.canEnableBluetooth && !sources.canEnableNfc) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        leading?.let { Column(Modifier.weight(1f)) { it() } }
        if (sources.canEnableBluetooth) {
            EnableSourceButton(
                icon = R.drawable.ic_bluetooth,
                onClick = sources.onEnableBluetooth,
                testTag = "ScannerEnableBluetooth",
                modifier = Modifier.weight(1f),
            )
        }
        if (sources.canEnableNfc) {
            EnableSourceButton(
                icon = R.drawable.ic_broadcast,
                onClick = sources.onEnableNfc,
                testTag = "ScannerEnableNfc",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun EnableSourceButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    SecondaryButton(
        text = null,
        size = ButtonSize.Small,
        onClick = onClick,
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Colors.White,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = modifier.testTag(testTag)
    )
}

@Composable
internal fun ScannerStatusPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(Colors.Black50)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("ScannerStatus")
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_broadcast),
            contentDescription = null,
            tint = Colors.White64,
            modifier = Modifier.size(16.dp)
        )
        Text13Up(
            text = text,
            color = Colors.White64,
        )
    }
}

internal fun scannerStatusText(sources: NearbyScanSources): Int = when {
    sources.isNfcActive && sources.isBluetoothActive -> R.string.other__scanner_status_qr_nfc_bluetooth
    sources.isNfcActive -> R.string.other__scanner_status_qr_nfc
    sources.isBluetoothActive -> R.string.other__scanner_status_qr_bluetooth
    else -> R.string.other__scanner_status_qr
}
