package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun Payments(
    viewModel: WalletViewModel,
) {
    Column {
        OutlinedCard {
            Text(
                text = "Pay Invoice",
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
                    onClick = { viewModel.send(invoiceToPay).also { invoiceToPay = "" } },
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
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedCard {
            Text(
                text = "Create Invoice",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            var amountToReceive by remember { mutableStateOf("") }
            Box {
                OutlinedTextField(
                    placeholder = { Text("Enter amount in sats") },
                    value = amountToReceive,
                    onValueChange = { amountToReceive = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val clipboard = LocalClipboardManager.current
            FullWidthTextButton(
                onClick = {
                    amountToReceive.toULongOrNull()?.let {
                        val bolt11 = viewModel.createInvoice(it)
                        clipboard.setText(AnnotatedString(bolt11))
                    }
                },
                enabled = amountToReceive.toULongOrNull() != null,
            ) {
                Text("Create bolt11")
            }
        }
    }

}
