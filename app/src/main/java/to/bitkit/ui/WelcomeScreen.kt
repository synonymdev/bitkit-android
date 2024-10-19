package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import to.bitkit.env.Env.SEED
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.AppShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(viewModel: WalletViewModel) {
    var showRestore by remember { mutableStateOf(false) }
    if (showRestore) {
        ModalBottomSheet(
            onDismissRequest = { showRestore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = AppShapes.sheet,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            RestoreView(viewModel)
        }
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        var bip39Passphrase by remember { mutableStateOf("") }
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Welcome",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                label = { Text("Optional BIP39 Passphrase") },
                value = bip39Passphrase,
                onValueChange = { bip39Passphrase = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider()
        FullWidthTextButton(
            onClick = { viewModel.createWallet(bip39Passphrase) },
            horizontalArrangement = Arrangement.Center,
        ) { Text("Create Wallet") }
        FullWidthTextButton(
            onClick = { showRestore = true },
            horizontalArrangement = Arrangement.Center,
        ) { Text("Restore Wallet") }
    }
}

@Composable
private fun RestoreView(
    viewModel: WalletViewModel,
    modifier: Modifier = Modifier,
) {
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp - 200.dp

    Column(modifier = modifier.height(sheetHeight)) {
        var bip39Mnemonic by remember { mutableStateOf(SEED) }
        var bip39Passphrase by remember { mutableStateOf("") }

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Restore Wallet",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                label = { Text("BIP39 Mnemonic") },
                value = bip39Mnemonic,
                onValueChange = { bip39Mnemonic = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                label = { Text("BIP39 Passphrase") },
                value = bip39Passphrase,
                onValueChange = { bip39Passphrase = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider()
        FullWidthTextButton(
            onClick = { viewModel.restoreWallet(bip39Passphrase, bip39Mnemonic) },
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Restore Wallet")
        }
    }
}
