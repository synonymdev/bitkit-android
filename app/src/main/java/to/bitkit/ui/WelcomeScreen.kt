package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            modifier = Modifier
                .fillMaxHeight()
                .padding(top = 100.dp)
        ) {
            RestoreView(
                onRestoreClick = { bip39Passphrase, bip39Mnemonic ->
                    viewModel.restoreWallet(bip39Passphrase, bip39Mnemonic)
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .imePadding()
            .systemBarsPadding()
            .fillMaxSize()
    ) {
        var bip39Passphrase by remember { mutableStateOf("") }
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Welcome",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
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
            onClick = {
                viewModel.createWallet(bip39Passphrase)
            },
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Create Wallet")
        }

        FullWidthTextButton(
            onClick = { showRestore = true },
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Restore Wallet")
        }
    }
}

@Composable
private fun RestoreView(
    onRestoreClick: (bip39Passphrase: String, bip39Mnemonic: String) -> Unit,
) {
    Column {
        var bip39Mnemonic by remember { mutableStateOf("") }
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
            onClick = { onRestoreClick(bip39Passphrase, bip39Mnemonic) },
            horizontalArrangement = Arrangement.Center,
            enabled = bip39Mnemonic.isNotBlank(),
        ) {
            Text("Restore Wallet")
        }
    }
}
