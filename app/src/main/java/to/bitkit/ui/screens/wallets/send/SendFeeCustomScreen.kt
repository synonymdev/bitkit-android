package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ext.toLongOrDefault
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LargeRow
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendFeeCustomScreen(
    onBack: () -> Unit,
    onContinue: (TransactionSpeed) -> Unit,
    viewModel: SendFeeViewModel,
) {
    val currentOnContinue by rememberUpdatedState(onContinue)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.shouldContinue, uiState.custom) {
        if (uiState.shouldContinue == true) {
            uiState.custom?.let { currentOnContinue(it) }
        }
    }

    Content(
        input = uiState.input,
        isPresetSelected = uiState.isPresetSelected,
        totalFeeText = uiState.totalFeeText,
        onKeyPress = viewModel::onKeyPress,
        onBack = onBack,
        onContinue = { viewModel.validateCustomFee() },
    )
}

@Composable
private fun Content(
    input: String,
    totalFeeText: String,
    modifier: Modifier = Modifier,
    isPresetSelected: Boolean = false,
    onKeyPress: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    val isValid = input.toLongOrDefault(0) != 0L
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("fee_screen")
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_fee_custom), onBack = onBack)
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
                modifier = Modifier.presetSelection(isPresetSelected),
            )

            if (isValid) {
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
                modifier = Modifier.testTag("continue_btn")
            )
            VerticalSpacer(16.dp)
        }
    }
}

/** Highlights a preset fee as selected text, so the user sees the next key replaces it. */
internal fun Modifier.presetSelection(selected: Boolean): Modifier =
    if (selected) background(Colors.Brand32, RoundedCornerShape(4.dp)) else this

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                input = "5",
                isPresetSelected = true,
                totalFeeText = "₿ 256 for this transaction ($0.25)",
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
