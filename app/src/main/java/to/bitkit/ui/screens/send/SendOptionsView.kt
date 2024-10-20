package to.bitkit.ui.screens.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import to.bitkit.ext.toast
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.screens.ScannerScreen

private object Routes {
    const val SEND_OPTIONS = "SEND_OPTIONS"
    const val SEND_MANUALLY = "SEND_MANUALLY"
    const val SEND_QR = "SEND_QR"
}

@Composable
fun SendOptionsView(
    modifier: Modifier = Modifier,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Send Bitcoin", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = Routes.SEND_OPTIONS,
        ) {
            composable(Routes.SEND_OPTIONS) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "TO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                    )
                    NavButton("Contact", showIcon = false) {
                        toast("Coming Soon")
                    }
                    val clipboard = LocalClipboardManager.current
                    NavButton("Paste Invoice", showIcon = false) {
                        val uri = clipboard.getText()?.text.orEmpty()
                        viewModel.onPasteFromClipboard(uri)
                    }
                    NavButton("Enter Manually") { navController.navigate(Routes.SEND_MANUALLY) }
                    NavButton("Scan QR Code") { navController.navigate(Routes.SEND_QR) }
                }
            }
            composable(Routes.SEND_MANUALLY) {
                SendEnterManuallyScreen()
            }
            composable(Routes.SEND_QR) {
                ScannerScreen()
            }
        }
    }
}
