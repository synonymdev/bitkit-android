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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
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
internal fun SignMessageSection(
    uiState: TrezorUiState,
    onMessageChange: (String) -> Unit,
    onSignMessage: () -> Unit,
    onVerifyMessage: () -> Unit,
) {
    Column {
        Caption13Up(
            text = "Sign Message",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.messageToSign,
            onValueChange = onMessageChange,
            label = { Caption("Message", color = Colors.White50) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Colors.White,
                unfocusedTextColor = Colors.White,
                focusedBorderColor = Colors.Brand,
                unfocusedBorderColor = Colors.White32,
                cursorColor = Colors.Brand,
            ),
            singleLine = true,
        )

        VerticalSpacer(16.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PrimaryButton(
                text = if (uiState.isSigningMessage) "Signing..." else "Sign Message",
                onClick = onSignMessage,
                enabled = !uiState.isSigningMessage &&
                    !uiState.isVerifyingMessage &&
                    uiState.messageToSign.isNotBlank(),
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = if (uiState.isVerifyingMessage) "Verifying..." else "Verify",
                onClick = onVerifyMessage,
                enabled = uiState.lastSignature != null && !uiState.isVerifyingMessage && !uiState.isSigningMessage,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = uiState.lastSignature != null) {
            uiState.lastSignature?.let { sig ->
                val onCopySignature = copyToClipboard(text = sig, label = "Signature")
                Column {
                    VerticalSpacer(16.dp)
                    Caption(
                        text = "Signature:",
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
                            text = sig,
                            color = Colors.Brand,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        HorizontalSpacer(8.dp)
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy signature",
                            tint = Colors.Brand,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableAlpha(onClick = onCopySignature)
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSignMessageSectionDefault() {
    AppThemeSurface {
        SignMessageSection(
            uiState = TrezorUiState(),
            onMessageChange = {},
            onSignMessage = {},
            onVerifyMessage = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSignMessageSectionWithSignature() {
    AppThemeSurface {
        SignMessageSection(
            uiState = TrezorPreviewData.uiStateWithSignature,
            onMessageChange = {},
            onSignMessage = {},
            onVerifyMessage = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSignMessageSectionSigning() {
    AppThemeSurface {
        SignMessageSection(
            uiState = TrezorUiState(isSigningMessage = true),
            onMessageChange = {},
            onSignMessage = {},
            onVerifyMessage = {},
        )
    }
}
