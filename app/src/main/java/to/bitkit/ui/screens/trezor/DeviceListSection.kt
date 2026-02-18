package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorTransportType
import to.bitkit.R
import to.bitkit.repositories.KnownDevice
import to.bitkit.ui.shared.modifiers.clickableAlpha
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
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.label ?: device.name ?: device.model ?: "Trezor",
                color = Colors.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (device.transportType) {
                    TrezorTransportType.USB -> "USB"
                    TrezorTransportType.BLUETOOTH -> "Bluetooth"
                },
                color = Colors.White50,
                fontSize = 12.sp,
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
                if (device.transportType == "bluetooth") {
                    R.drawable.ic_broadcast
                } else {
                    R.drawable.ic_git_branch
                }
            ),
            contentDescription = null,
            tint = if (isConnected) Colors.Green else Colors.White64,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.label ?: device.name ?: device.model ?: "Trezor",
                color = Colors.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (device.transportType == "bluetooth") "Bluetooth" else "USB",
                    color = Colors.White50,
                    fontSize = 12.sp,
                )
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    color = if (isConnected) Colors.Green else Colors.White32,
                    fontSize = 12.sp,
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
