package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Env.SEED
import to.bitkit.ui.shared.InfoField

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: WalletViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier,
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Lightning Node",
            style = MaterialTheme.typography.titleMedium,
        )
        SettingButton(
            label = "Peers",
            onClick = { navController.navigate(Routes.Peers.destination) }
        )
        SettingButton(
            label = "Channels",
            onClick = { navController.navigate(Routes.Channels.destination) }
        )
        SettingButton(
            label = "Payments",
            onClick = { navController.navigate(Routes.Payments.destination) }
        )

        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Bitcoin Wallet",
            style = MaterialTheme.typography.titleMedium,
        )
        Mnemonic(SEED) // TODO use value from viewModel.uiState
    }
}

@Composable
private fun SettingButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(16.dp, 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(top = 4.dp)
    ) {
        Text(text = label)
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun Mnemonic(
    mnemonic: String,
) {
    InfoField(
        value = mnemonic,
        label = stringResource(R.string.mnemonic),
        maxLength = 52,
        trailingIcon = { CopyToClipboardButton(mnemonic) },
    )
}
