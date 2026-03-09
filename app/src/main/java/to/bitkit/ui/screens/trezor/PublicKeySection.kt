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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.repositories.TrezorState
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard

@Composable
internal fun PublicKeySection(
    trezorState: TrezorState,
    uiState: TrezorUiState,
    onGetPublicKey: (Boolean) -> Unit,
) {
    val accountPath = remember(uiState.derivationPath) {
        uiState.derivationPath.split("/").take(4).joinToString("/")
    }

    Column {
        Footnote(
            text = "Public Key (xpub)",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        Text(
            text = "Account path: $accountPath",
            color = Colors.White50,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )

        VerticalSpacer(12.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = if (uiState.isGettingPublicKey) "Getting..." else "Get xpub",
                onClick = { onGetPublicKey(false) },
                enabled = !uiState.isGettingPublicKey,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (uiState.isGettingPublicKey) "Getting..." else "Show on Device",
                onClick = { onGetPublicKey(true) },
                enabled = !uiState.isGettingPublicKey,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = trezorState.lastPublicKey != null) {
            trezorState.lastPublicKey?.let { response ->
                val onCopyXpub = copyToClipboard(text = response.xpub, label = "xpub")
                val onCopyPublicKey = copyToClipboard(text = response.publicKey, label = "Public Key")
                Column {
                    VerticalSpacer(12.dp)
                    Footnote(
                        text = "xpub:",
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
                        Text(
                            text = response.xpub,
                            color = Colors.Brand,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        HorizontalSpacer(8.dp)
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy xpub",
                            tint = Colors.Brand,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableAlpha(onClick = onCopyXpub)
                        )
                    }

                    VerticalSpacer(12.dp)
                    Footnote(
                        text = "Public Key:",
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
                        Text(
                            text = response.publicKey,
                            color = Colors.Brand,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        HorizontalSpacer(8.dp)
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy public key",
                            tint = Colors.Brand,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableAlpha(onClick = onCopyPublicKey)
                        )
                    }
                }
            }
        }
    }
}
