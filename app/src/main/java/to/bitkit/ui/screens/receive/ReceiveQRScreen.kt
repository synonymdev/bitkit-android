package to.bitkit.ui.screens.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.shared.PagerWithIndicator
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ReceiveQRScreen(
    modifier: Modifier = Modifier,
) {
    val uri = "bitcoin:bip or bolt11 or cjitInvoice"
    var cjitActive by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .height(LocalConfiguration.current.screenHeightDp.dp - 100.dp)
            .fillMaxWidth()
            .padding(20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "Receive Bitcoin", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(24.dp))

            val pagerState = rememberPagerState(initialPage = 0) { 2 }
            PagerWithIndicator(pagerState) {
                when (it) {
                    0 -> ReceiveQrSlide(uri)
                    1 -> CopyAddressCard("On-chain Address", "onchainAddress")
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Column {
            Text(
                text = "Want to receive lighting funds?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
            )
            Row {
                Text(
                    text = "Receive on Spending Balance",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = cjitActive,
                    onCheckedChange = { cjitActive = it }
                )
            }
        }
    }
}

@Composable
private fun ReceiveQrSlide(
    uri: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QrCodeImage(uri)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            val buttonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(
                onClick = { /*TODO*/ },
                colors = buttonColors,
            ) {
                Text("Edit")
            }
            val clipboard = LocalClipboardManager.current
            TextButton(
                onClick = { clipboard.setText(AnnotatedString((uri))) },
                colors = buttonColors,
            ) {
                Text("Copy")
            }
            TextButton(
                onClick = { /*TODO*/ },
                colors = buttonColors,
            ) {
                Text("Share")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReceiveQRScreenPreview() {
    AppThemeSurface {
        ReceiveQRScreen()
    }
}

@Composable
private fun CopyAddressCard(
    title: String,
    address: String,
) {
    Column(
        modifier = Modifier,
    ) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 20.dp, end = 20.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                )
                Row {
                    val buttonColors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    val clipboard = LocalClipboardManager.current
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString((address))) },
                        colors = buttonColors,
                    ) {
                        Text("Copy")
                    }
                    TextButton(
                        onClick = { /*TODO*/ },
                        colors = buttonColors,
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CopyAddressCardPreview() {
    AppThemeSurface {
        CopyAddressCard(
            title = "On-chain Address",
            address = "any bitcoin address"
        )
    }
}
