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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import to.bitkit.ui.MainUiState
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.shared.PagerWithIndicator
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface

private object Routes {
    const val QR = "qr_screen"
    const val CJIT = "cjit_screen"
}

@Composable
fun ReceiveQRScreen(
    walletState: MainUiState,
    modifier: Modifier = Modifier,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val cjitInvoice = remember { mutableStateOf<String?>(null) }
    val cjitActive = remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Receive Bitcoin",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(24.dp))

        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = Routes.QR,
        ) {
            composable(Routes.QR) {
                ContentView(
                    cjitInvoice = cjitInvoice,
                    cjitActive = cjitActive,
                    navController = navController,
                    walletState = walletState,
                )
            }
            composable(Routes.CJIT) {
                ReceiveCjitScreen(
                    viewModel = viewModel,
                    onCjitCreated = { invoice ->
                        cjitInvoice.value = invoice
                        navController.navigate(Routes.QR) {
                            popUpTo(Routes.QR) { inclusive = true }
                        }
                    },
                    onDismiss = { cjitActive.value = !cjitInvoice.value.isNullOrBlank() }
                )
            }
        }
    }
}

@Composable
private fun ContentView(
    cjitInvoice: MutableState<String?>,
    cjitActive: MutableState<Boolean>,
    navController: NavHostController,
    walletState: MainUiState,
) {
    Column {
        val onchainAddress = walletState.onchainAddress
        val uri = cjitInvoice.value ?: walletState.bip21
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            val pagerState = rememberPagerState(initialPage = 0) { 2 }
            PagerWithIndicator(pagerState) {
                when (it) {
                    0 -> ReceiveQrSlide(uri)
                    1 -> CopyValuesSlide(
                        onchainAddress = onchainAddress,
                        bolt11 = walletState.bolt11,
                        cjitInvoice = cjitInvoice.value,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Column {
            if (cjitInvoice.value == null) {
                Text(
                    text = "Want to receive lighting funds?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Normal,
                )
            }
            Row {
                Text(
                    text = "Receive on Spending Balance",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = cjitActive.value,
                    onCheckedChange = {
                        cjitActive.value = it
                        if (it) {
                            navController.navigate(Routes.CJIT)
                        } else {
                            cjitInvoice.value = null
                        }
                    }
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
            val context = LocalContext.current
            TextButton(
                onClick = { shareText(context, uri) },
                colors = buttonColors,
            ) {
                Text("Share")
            }
        }
    }
}

// TODO fix preview with viewModel
// @Preview(showBackground = true)
// @Composable
// private fun ReceiveQRScreenPreview() {
//     AppThemeSurface {
//         ReceiveQRScreen()
//     }
// }

@Composable
private fun CopyValuesSlide(
    onchainAddress: String,
    bolt11: String,
    cjitInvoice: String?,
) {
    Column {
        if (onchainAddress.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            CopyAddressCard(
                title = "On-chain Address",
                address = onchainAddress,
            )
        }
        if (bolt11.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            CopyAddressCard(
                title = "Lightning Invoice",
                address = bolt11,
            )
        } else if (cjitInvoice != null) {
            Spacer(modifier = Modifier.height(16.dp))
            CopyAddressCard(
                title = "Lightning Invoice",
                address = cjitInvoice,
            )
        }
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
                    val context = LocalContext.current
                    TextButton(
                        onClick = { shareText(context, address) },
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
