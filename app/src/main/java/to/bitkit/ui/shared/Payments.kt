package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.WalletViewModel

@Composable
fun Payments(
    viewModel: WalletViewModel,
) {
    OutlinedCard {
        Text(
            text = "Payments",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp)
        )
        var invoiceToPay by remember { mutableStateOf("") }
        Box {
            OutlinedTextField(
                label = { Text("Paste invoice") },
                value = invoiceToPay,
                onValueChange = { invoiceToPay = it },
                textStyle = MaterialTheme.typography.labelSmall,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            IconButton(
                onClick = { viewModel.payInvoice(invoiceToPay).also { invoiceToPay = "" } },
                enabled = invoiceToPay.isNotBlank(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.RocketLaunch,
                    contentDescription = stringResource(R.string.pay),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        val clipboard = LocalClipboardManager.current
        FullWidthTextButton(
            onClick = { clipboard.setText(AnnotatedString((viewModel.createInvoice()))) }) {
            Text("Copy new invoice to clipboard")
        }
    }
}
