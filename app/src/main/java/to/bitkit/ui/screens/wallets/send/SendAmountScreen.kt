package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.OutlinedColorButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAmountScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar(stringResource(R.string.title_send_amount)) {
            onEvent(SendEvent.AmountReset)
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            TextField(
                placeholder = { Text(stringResource(R.string.amount_placeholder)) },
                value = uiState.amountInput,
                onValueChange = { onEvent(SendEvent.AmountChange(it)) },
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.small,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                if (uiState.isUnified) {
                    OutlinedColorButton(
                        onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                        color = if (uiState.payMethod == SendMethod.ONCHAIN)
                            colorScheme.primary else colorScheme.secondary
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.payMethod == SendMethod.ONCHAIN) R.string.savings else R.string.spending
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.continue_button),
                enabled = uiState.isAmountInputValid,
                onClick = { onEvent(SendEvent.AmountContinue(uiState.amountInput)) },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@LightModePreview
@DarkModePreview
@Composable
private fun SendAmountViewPreview() {
    AppThemeSurface {
        SendAmountScreen(
            uiState = SendUiState(
                isUnified = true,
                payMethod = SendMethod.LIGHTNING,
            ),
            onBack = {},
            onEvent = {},
        )
    }
}
