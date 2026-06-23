package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorTransportType
import to.bitkit.R
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
internal fun DeviceCard(
    device: TrezorDeviceInfo,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Colors.White16, RoundedCornerShape(12.dp))
            .background(Colors.White06)
            .clickableAlpha(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(
                when (device.transportType) {
                    TrezorTransportType.USB -> R.drawable.ic_git_branch
                    TrezorTransportType.BLUETOOTH -> R.drawable.ic_broadcast
                }
            ),
            contentDescription = null,
            tint = Colors.White64,
            modifier = Modifier.size(24.dp)
        )
        HorizontalSpacer(12.dp)
        Column(modifier = Modifier.weight(1f)) {
            CaptionB(
                text = device.label ?: device.name ?: device.model ?: "Trezor",
                color = Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Caption(
                text = when (device.transportType) {
                    TrezorTransportType.USB -> "USB"
                    TrezorTransportType.BLUETOOTH -> "Bluetooth"
                },
                color = Colors.White50,
            )
        }
    }
}

@Composable
internal fun KnownDeviceCard(
    device: KnownDevice,
    isConnected: Boolean,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    val borderColor = if (isConnected) Colors.Green else Colors.White16
    val backgroundColor = if (isConnected) Colors.Green.copy(alpha = 0.08f) else Colors.White06

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickableAlpha(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(
                when (device.transportType) {
                    TransportType.BLUETOOTH -> R.drawable.ic_broadcast
                    TransportType.USB -> R.drawable.ic_git_branch
                }
            ),
            contentDescription = null,
            tint = if (isConnected) Colors.Green else Colors.White64,
            modifier = Modifier.size(24.dp)
        )
        HorizontalSpacer(12.dp)
        Column(modifier = Modifier.weight(1f)) {
            CaptionB(
                text = device.label ?: device.name ?: device.model ?: "Trezor",
                color = Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Caption(
                    text = when (device.transportType) {
                        TransportType.BLUETOOTH -> "Bluetooth"
                        TransportType.USB -> "USB"
                    },
                    color = Colors.White50,
                )
                Caption(
                    text = if (isConnected) "Connected" else "Disconnected",
                    color = if (isConnected) Colors.Green else Colors.White32,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_trash),
            contentDescription = "Forget device",
            tint = Colors.White32,
            modifier = Modifier
                .size(20.dp)
                .clickableAlpha(onClick = onForget)
        )
    }
}

@Preview
@Composable
private fun PreviewDeviceCardBluetooth() {
    AppThemeSurface {
        DeviceCard(device = TrezorPreviewData.sampleNearbyDevice, onClick = {})
    }
}

@Preview
@Composable
private fun PreviewDeviceCardUsb() {
    AppThemeSurface {
        DeviceCard(device = TrezorPreviewData.sampleNearbyDeviceUsb, onClick = {})
    }
}

@Preview
@Composable
private fun PreviewKnownDeviceCardConnected() {
    AppThemeSurface {
        KnownDeviceCard(
            device = TrezorPreviewData.sampleKnownDevice,
            isConnected = true,
            onClick = {},
            onForget = {},
        )
    }
}

@Preview
@Composable
private fun PreviewKnownDeviceCardDisconnected() {
    AppThemeSurface {
        KnownDeviceCard(
            device = TrezorPreviewData.sampleKnownDeviceBle,
            isConnected = false,
            onClick = {},
            onForget = {},
        )
    }
}
