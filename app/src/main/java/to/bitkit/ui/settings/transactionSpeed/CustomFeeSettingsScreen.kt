package to.bitkit.ui.settings.transactionSpeed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.LargeRow
import to.bitkit.ui.components.PinNumberPad
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun CustomFeeSettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val customFeeRate = app.defaultTransactionSpeed.collectAsStateWithLifecycle()
    var input by remember {
        mutableStateOf((customFeeRate.value as? TransactionSpeed.Custom)?.satsPerVByte?.toString() ?: "")
    }

    CustomFeeSettingsContent(
        input = input,
        onKeyPress = { key ->
            when (key) {
                KEY_DELETE -> input = if (input.isNotEmpty()) input.dropLast(1) else ""
                else -> if (input.length < 3) input = (input + key).trimStart('0')
            }
        },
        onContinue = {
            val feeRate = input.toUIntOrNull() ?: 0u
            app.setDefaultTransactionSpeed(TransactionSpeed.Custom(feeRate))
            navController.popBackStack()
        },
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.popBackStack() },
    )
}

@Composable
private fun CustomFeeSettingsContent(
    input: String,
    onKeyPress: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val feeRate = input.toUIntOrNull() ?: 0u
    ScreenColumn(
        modifier = Modifier.navigationBarsPadding()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__general__speed_fee_custom),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Caption13Up(text = stringResource(R.string.common__sat_vbyte), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))
            LargeRow(
                prefix = null,
                text = if (input.isEmpty()) "0" else input,
                symbol = BITCOIN_SYMBOL,
                showSymbol = true,
            )
            // TODO add subtitle text (B 250 for average transaction ($10.2)

            Spacer(modifier = Modifier.weight(1f))

            PinNumberPad(
                onPress = onKeyPress,
                modifier = Modifier.height(350.dp)
            )
            PrimaryButton(
                onClick = onContinue,
                enabled = feeRate != 0u,
                text = stringResource(R.string.common__continue),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        CustomFeeSettingsContent(
            input = "5",
        )
    }
}
