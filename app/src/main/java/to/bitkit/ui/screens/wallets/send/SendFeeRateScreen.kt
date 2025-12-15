package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.FeeRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendUiState

@Composable
fun SendFeeRateScreen(
    sendUiState: SendUiState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSelect: (TransactionSpeed) -> Unit,
    viewModel: SendFeeViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.init(sendUiState)
    }

    Content(
        uiState = uiState,
        onBack = onBack,
        onContinue = onContinue,
        onSelect = { onSelect(it.toSpeed()) },
    )
}

@Composable
private fun Content(
    uiState: SendFeeUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
    onSelect: (FeeRate) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("speed_screen")
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_fee_speed), onBack = onBack)
        if (uiState.fees.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Colors.White32,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.Center)
                )
            }
            return
        }
        SectionHeader(
            title = stringResource(R.string.wallet__send_fee_and_speed),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        uiState.fees.map { (feeRate, sats) ->
            FeeItem(
                feeRate = feeRate,
                sats = sats,
                isSelected = uiState.selected == feeRate,
                isDisabled = feeRate in uiState.disabledRates,
                onClick = { if (feeRate !in uiState.disabledRates) onSelect(feeRate) },
                modifier = Modifier.testTag("fee_${feeRate.name}_button"),
            )
        }

        FillHeight(min = 16.dp)

        PrimaryButton(
            text = stringResource(R.string.common__continue),
            onClick = onContinue,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("continue_btn")
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun FeeItem(
    feeRate: FeeRate,
    sats: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isDisabled: Boolean = false,
    unit: PrimaryDisplay = LocalCurrencies.current.primaryDisplay,
) {
    val color = if (isDisabled) Colors.Gray3 else MaterialTheme.colorScheme.primary
    val accent = if (isDisabled) Colors.Gray3 else MaterialTheme.colorScheme.secondary
    Column(
        modifier = modifier
            .clickableAlpha(onClick = onClick)
            .then(
                if (isSelected) Modifier.background(Colors.White06) else Modifier
            ),
    ) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(90.dp)
        ) {
            Icon(
                painter = painterResource(feeRate.icon),
                contentDescription = null,
                tint = when {
                    isDisabled -> Colors.Gray3
                    else -> feeRate.color
                },
                modifier = Modifier.size(32.dp),
            )
            HorizontalSpacer(16.dp)
            Column {
                BodyMSB(stringResource(feeRate.title), color = color)
                BodySSB(stringResource(feeRate.description), color = accent)
            }
            FillWidth()
            if (sats != 0L) {
                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    MoneyMSB(sats, color = color, accent = accent)
                    MoneySSB(sats, unit = unit.not(), color = accent, accent = accent)
                }
            }
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = SendFeeUiState(
                    fees = mapOf(
                        FeeRate.FAST to 4000L,
                        FeeRate.NORMAL to 3000L,
                        FeeRate.SLOW to 2000L,
                        FeeRate.CUSTOM to 0L,
                    ),
                    selected = FeeRate.NORMAL,
                ),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true)
@Composable
private fun PreviewCustom() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = SendFeeUiState(
                    fees = mapOf(
                        FeeRate.FAST to 4000L,
                        FeeRate.NORMAL to 3000L,
                        FeeRate.SLOW to 2000L,
                        FeeRate.CUSTOM to 6000L,
                    ),
                    selected = FeeRate.CUSTOM,
                ),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = SendFeeUiState(),
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
