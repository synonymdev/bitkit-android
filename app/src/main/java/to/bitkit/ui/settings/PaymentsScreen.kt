package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.CopyToClipboardButton
import to.bitkit.ui.InfoField
import to.bitkit.ui.MainViewModel
import to.bitkit.ui.payInvoice

@Composable
fun PaymentsScreen(
    viewModel: MainViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        var invoiceToPay by remember { mutableStateOf("") }
        OutlinedTextField(
            label = { Text("Pay invoice") },
            value = invoiceToPay,
            onValueChange = { invoiceToPay = it },
            textStyle = MaterialTheme.typography.labelSmall,
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { payInvoice(invoiceToPay) }) {
            Text(text = stringResource(R.string.pay))
        }

        val invoiceToSend by remember { mutableStateOf(viewModel.createInvoice()) }
        InfoField(
            label = "Send invoice",
            value = invoiceToSend,
            trailingIcon = { CopyToClipboardButton(invoiceToSend) },
        )
    }
}
