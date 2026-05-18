package to.bitkit.ui.screens.trezor

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.repositories.TrezorState
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard

@Composable
internal fun AddressSection(
    trezorState: TrezorState,
    uiState: TrezorUiState,
    onGetAddress: (Boolean) -> Unit,
    onIncrementIndex: () -> Unit,
) {
    Column {
        Caption13Up(
            text = "Address Generation",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        Caption(
            text = "Path: ${uiState.derivationPath}",
            color = Colors.White50,
        )

        VerticalSpacer(16.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = if (uiState.isGettingAddress) "Getting..." else "Get Address",
                onClick = { onGetAddress(false) },
                enabled = !uiState.isGettingAddress,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (uiState.isGettingAddress) "Getting..." else "Show on Device",
                onClick = { onGetAddress(true) },
                enabled = !uiState.isGettingAddress,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = trezorState.lastAddress != null) {
            trezorState.lastAddress?.let { response ->
                val onCopyAddress = copyToClipboard(text = response.address, label = "Address")
                Column {
                    VerticalSpacer(16.dp)
                    Caption(
                        text = "Address:",
                        color = Colors.White50,
                    )
                    VerticalSpacer(4.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Colors.Brand.copy(alpha = 0.1f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Caption(
                            text = response.address,
                            color = Colors.Brand,
                            modifier = Modifier.weight(1f),
                        )
                        HorizontalSpacer(8.dp)
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy address",
                            tint = Colors.Brand,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableAlpha(onClick = onCopyAddress)
                        )
                    }
                    VerticalSpacer(8.dp)
                    SecondaryButton(
                        text = "Next Index",
                        onClick = onIncrementIndex,
                        size = ButtonSize.Small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewAddressSectionEmpty() {
    AppThemeSurface {
        AddressSection(
            trezorState = TrezorPreviewData.connectedState,
            uiState = TrezorUiState(),
            onGetAddress = {},
            onIncrementIndex = {},
        )
    }
}

@Preview
@Composable
private fun PreviewAddressSectionWithAddress() {
    AppThemeSurface {
        AddressSection(
            trezorState = TrezorPreviewData.connectedStateWithResults,
            uiState = TrezorUiState(),
            onGetAddress = {},
            onIncrementIndex = {},
        )
    }
}

@Preview
@Composable
private fun PreviewAddressSectionLoading() {
    AppThemeSurface {
        AddressSection(
            trezorState = TrezorPreviewData.connectedState,
            uiState = TrezorUiState(isGettingAddress = true),
            onGetAddress = {},
            onIncrementIndex = {},
        )
    }
}
