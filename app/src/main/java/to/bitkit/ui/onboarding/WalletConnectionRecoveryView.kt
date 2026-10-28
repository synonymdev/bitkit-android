package to.bitkit.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ConnectionRecoveryUiState

@Composable
fun WalletConnectionRecoveryView(
    state: ConnectionRecoveryUiState,
    onBack: () -> Unit,
    onElectrum: (String) -> Unit,
    onRgs: (String) -> Unit,
    onResetElectrum: () -> Unit,
    onResetRgs: () -> Unit,
    onSave: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScreenColumn(modifier = Modifier.testTag("WalletConnectionRecovery")) {
        AppTopBar(titleText = stringResource(R.string.onboarding__connection_settings), onBackClick = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding()
        ) {
            BodyM(stringResource(R.string.settings__adv__electrum_server))
            TextInput(
                value = state.electrum,
                onValueChange = onElectrum,
                enabled = !state.busy && state.owner != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("RecoveryElectrumInput")
            )
            SecondaryButton(
                text = stringResource(R.string.settings__es__button_reset),
                onClick = onResetElectrum,
                enabled = !state.busy && state.owner != null,
                modifier = Modifier.testTag("RecoveryElectrumReset")
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                onClick = { onSave(true) },
                enabled = !state.busy && state.owner != null && state.electrum.isNotBlank(),
                modifier = Modifier.testTag("RecoveryElectrumSave")
            )
            VerticalSpacer(24.dp)
            BodyM(stringResource(R.string.settings__rgs__server_url))
            TextInput(
                value = state.rgs,
                onValueChange = onRgs,
                enabled = !state.busy && state.owner != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("RecoveryRgsInput")
            )
            SecondaryButton(
                text = stringResource(R.string.settings__es__button_reset),
                onClick = onResetRgs,
                enabled = !state.busy && state.owner != null,
                modifier = Modifier.testTag("RecoveryRgsReset")
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                onClick = { onSave(false) },
                enabled = !state.busy && state.owner != null,
                modifier = Modifier.testTag("RecoveryRgsSave")
            )
            VerticalSpacer(24.dp)
            if (state.saved) {
                BodyM(
                    stringResource(R.string.onboarding__connection_saved),
                    modifier = Modifier.testTag("RecoverySaved")
                )
            }
            state.error?.let { BodyM(it, color = Colors.Red, modifier = Modifier.testTag("RecoveryError")) }
            PrimaryButton(
                text = stringResource(R.string.common__try_again),
                onClick = onRetry,
                enabled = !state.busy,
                modifier = Modifier.testTag("RecoveryRetry")
            )
            VerticalSpacer(16.dp)
        }
    }
}
