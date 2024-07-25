package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.InfoField

@Composable
fun WalletScreen(
    viewModel: MainViewModel,
    content: @Composable () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.lightning),
                style = MaterialTheme.typography.titleLarge,
            )
            val nodeId by remember { viewModel.ldkNodeId }
            InfoField(
                value = nodeId,
                label = stringResource(R.string.node_id),
                trailingIcon = { CopyToClipboardButton(nodeId) },
            )
            val ldkBalance by remember { viewModel.ldkBalance }
            InfoField(
                value = ldkBalance,
                label = stringResource(R.string.balance),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Wallet",
                style = MaterialTheme.typography.titleLarge,
            )
            val address by remember { viewModel.btcAddress }
            InfoField(
                value = address,
                label = stringResource(R.string.address),
                trailingIcon = {
                    Row {
                        IconButton(onClick = viewModel::getNewAddress) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        CopyToClipboardButton(address)
                    }
                },
            )
            val btcBalance by remember { viewModel.btcBalance }
            InfoField(
                value = btcBalance,
                label = stringResource(R.string.balance),
                trailingIcon = {
                    IconButton(onClick = viewModel::sync) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.sync),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
        content()
    }
}

@Composable
internal fun CopyToClipboardButton(text: String) {
    val clipboardManager = LocalClipboardManager.current
    IconButton(onClick = { clipboardManager.setText(AnnotatedString((text))) }) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    }
}

