package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.CopyToClipboardButton
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.moneyString

@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    uiState: MainUiState.Content,
    content: @Composable () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.lightning),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = moneyString(uiState.ldkBalance),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            InfoField(
                value = uiState.ldkNodeId,
                label = stringResource(R.string.node_id),
                maxLength = 44,
                trailingIcon = { CopyToClipboardButton(uiState.ldkNodeId) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.wallet),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = moneyString(uiState.btcBalance?.toLong()),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            InfoField(
                value = uiState.btcAddress,
                label = stringResource(R.string.address),
                maxLength = 36,
                trailingIcon = {
                    Row {
                        IconButton(onClick = viewModel::getNewAddress) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        CopyToClipboardButton(uiState.btcAddress)
                    }
                },
            )
        }
        content()
        Spacer(modifier = Modifier.height(1.dp))
    }
}
