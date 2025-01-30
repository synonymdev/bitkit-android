package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendUiState
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAddressScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar(stringResource(R.string.title_send)) {
            onEvent(SendEvent.AddressReset)
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            Text(
                text = stringResource(R.string.wallet__send_to),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                placeholder = { Text(stringResource(R.string.address_placeholder)) },
                value = uiState.addressInput,
                onValueChange = { onEvent(SendEvent.AddressChange(it)) },
                minLines = 12,
                maxLines = 12,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(R.string.continue_button),
                enabled = uiState.isAddressInputValid,
                onClick = { onEvent(SendEvent.AddressContinue(uiState.addressInput)) },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

}

@LightModePreview
@DarkModePreview
@Composable
private fun SendEnterManuallyScreenPreview() {
    AppThemeSurface {
        SendAddressScreen(
            uiState = SendUiState(),
            onBack = {},
            onEvent = {},
        )
    }
}
