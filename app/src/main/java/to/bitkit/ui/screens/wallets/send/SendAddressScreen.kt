package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendUiState

@Composable
fun SendAddressScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_bitcoin)) {
            onEvent(SendEvent.AddressReset)
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            VerticalSpacer(16.dp)
            Caption13Up(stringResource(R.string.wallet__send_to), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                placeholder = stringResource(R.string.wallet__send_address_placeholder),
                value = uiState.addressInput,
                onValueChange = { onEvent(SendEvent.AddressChange(it)) },
                minLines = 12,
                textStyle = AppTextStyles.Title,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .weight(1f)
            )
            VerticalSpacer(16.dp)
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                enabled = uiState.isAddressInputValid,
                onClick = { onEvent(SendEvent.AddressContinue(uiState.addressInput)) },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column {
            VerticalSpacer(100.dp)
            SendAddressScreen(
                uiState = SendUiState(),
                onBack = {},
                onEvent = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Column {
            VerticalSpacer(100.dp)
            SendAddressScreen(
                uiState = SendUiState(
                    addressInput = "bitcoin:bc17tq4mtkq86vte7a26e0za560kgflwqsvxznmer5?lightning=LNBC1PQUVNP8KHGPLNF6REGS3VY5F40AJFUN4S2JUDQQNP4TK9MP6LWWLWTC3XX3UUEVYZ4EVQU3X4NQDX348QPP5WJC9DWNTAFN7FZEZFVDC3MHV67SX2LD2MG602E3LEZDMFT29JLWQSP54QKM4G8A2KD5RGEKACA3CH4XV4M2MQDN62F8S2CCRES9QYYSGQCQPCXQRRSSRZJQWQKZS03MNNHSTKR9DN2XQRC8VW5X6CEWAL8C6RW6QQ3T02T3R",
                    isAddressInputValid = true
                ),
                onBack = {},
                onEvent = {},
            )
        }
    }
}
