package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.TrezorFeatures
import to.bitkit.ui.components.Caption
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
internal fun ConnectedDeviceInfo(features: TrezorFeatures) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Colors.White06)
            .padding(12.dp)
    ) {
        InfoRow("Label", features.label ?: "-")
        InfoRow("Model", features.model ?: "-")
        InfoRow(
            "Firmware",
            "${features.majorVersion ?: 0}.${features.minorVersion ?: 0}.${features.patchVersion ?: 0}"
        )
        InfoRow("PIN", if (features.pinProtection == true) "Enabled" else "Disabled")
        InfoRow("Passphrase", if (features.passphraseProtection == true) "Enabled" else "Disabled")
    }
}

@Preview
@Composable
private fun PreviewConnectedDeviceInfo() {
    AppThemeSurface {
        ConnectedDeviceInfo(TrezorPreviewData.sampleFeatures)
    }
}

@Preview
@Composable
private fun PreviewConnectedDeviceInfoMinimal() {
    AppThemeSurface {
        ConnectedDeviceInfo(TrezorPreviewData.sampleFeaturesMinimal)
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Caption(
            text = label,
            color = Colors.White50,
        )
        Caption(
            text = value,
            color = Colors.White,
        )
    }
}
