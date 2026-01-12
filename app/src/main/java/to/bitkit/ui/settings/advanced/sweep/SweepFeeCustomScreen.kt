package to.bitkit.ui.settings.advanced.sweep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import to.bitkit.ext.toLongOrDefault
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.LargeRow
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SweepViewModel

@Composable
fun SweepFeeCustomScreen(
    navController: NavController,
    viewModel: SweepViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    val estimatedVsize = uiState.transactionPreview?.estimatedVsize ?: 0u
    val feeRate = input.toLongOrDefault(0)
    val totalFee = feeRate * estimatedVsize.toLong()
    val totalFeeText = if (totalFee > 0) {
        stringResource(
            R.string.sweep__balance_format,
            totalFee.formatToModernDisplay(),
        ) + " " + stringResource(R.string.sweep__custom_fee_total)
    } else {
        ""
    }

    Content(
        input = input,
        totalFeeText = totalFeeText,
        onKeyPress = { key ->
            when (key) {
                KEY_DELETE -> input = input.dropLast(1)
                else -> {
                    if (input.length < 6) {
                        input += key
                    }
                }
            }
        },
        onBack = { navController.popBackStack() },
        onContinue = {
            val rate = input.toUIntOrNull() ?: 1u
            viewModel.setFeeRate(TransactionSpeed.Custom(rate))
            navController.popBackStack()
        },
    )
}

@Composable
private fun Content(
    input: String,
    totalFeeText: String,
    onKeyPress: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    val isValid = input.toLongOrDefault(0) >= 1L

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.sweep__custom_fee_nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            SectionHeader(title = stringResource(R.string.common__sat_vbyte))

            LargeRow(
                prefix = null,
                text = input.ifEmpty { "0" },
                symbol = BITCOIN_SYMBOL,
                showSymbol = true,
            )

            if (isValid && totalFeeText.isNotEmpty()) {
                VerticalSpacer(28.dp)
                BodyM(totalFeeText, color = Colors.White64)
            }

            FillHeight()

            NumberPad(
                onPress = onKeyPress,
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(350.dp)
            )

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = isValid,
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            input = "5",
            totalFeeText = "₿ 256 for this transaction",
        )
    }
}
