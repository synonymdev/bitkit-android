package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.FeeRate
import to.bitkit.repositories.SparkExitFeeBumpOption
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.send.FeeItem
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/**
 * Raises a running exit's fee rate: the network's rates above it, each with what it costs from savings, then the
 * payment, after which the exit rebuilds what has not confirmed at the new rate.
 */
@Composable
fun SparkExitFeeBumpScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.loadFeeBump()
        viewModel.effects.collect { if (it == SparkExitEffect.FeeBumped) onDone() }
    }
    Content(
        uiState = uiState,
        onBack = onBack,
        onSelect = viewModel::selectFeeBump,
        onRetry = viewModel::loadFeeBump,
        onPay = viewModel::payHigherFee,
    )
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onSelect: (FeeRate) -> Unit = {},
    onRetry: () -> Unit = {},
    onPay: () -> Unit = {},
) {
    val bump = uiState.feeBump
    val selected = bump.selectedOption
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                VerticalSpacer(32.dp)
                Display(text = stringResource(R.string.lightning__spark_exit__fee_bump_title).withAccent())
                BodyM(
                    text = stringResource(R.string.lightning__spark_exit__fee_bump_text)
                        .replace("{rate}", "${uiState.record?.feeRateSatPerVbyte ?: 0uL}")
                        .withAccentBoldBright(),
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)
            }
            when {
                bump.isLoading -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Colors.White32)
                }
                bump.isLeavingLeaves -> BodyM(
                    text = stringResource(R.string.lightning__spark_exit__fee_bump_leaves_behind),
                    color = Colors.White64,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("SparkExitFeeBumpLeavesBehind")
                )
                bump.hasError -> BodyM(
                    text = stringResource(R.string.lightning__spark_exit__fee_bump_error),
                    color = Colors.White64,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("SparkExitFeeBumpError")
                )
                else -> {
                    bump.options.forEach { option ->
                        FeeItem(
                            feeRate = option.speed,
                            sats = option.totalSats.toLong(),
                            description = "${stringResource(option.speed.description)} · " +
                                stringResource(R.string.lightning__spark_exit__fee_bump_rate)
                                    .replace("{rate}", "${option.rateSatPerVbyte}"),
                            isSelected = option == selected,
                            isDisabled = !bump.canPay(option),
                            showsSymbols = true,
                            onClick = { if (bump.canPay(option)) onSelect(option.speed) },
                            modifier = Modifier.testTag("SparkExitFeeBump${option.speed.name}")
                        )
                    }
                }
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (bump.isSavingsShort) {
                Caption(
                    text = stringResource(R.string.lightning__spark_exit__fund_insufficient),
                    color = Colors.White64,
                    modifier = Modifier.testTag("SparkExitFeeBumpInsufficient")
                )
                VerticalSpacer(16.dp)
            }
            if (bump.hasError) {
                PrimaryButton(
                    text = stringResource(R.string.common__try_again),
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitFeeBumpRetry")
                )
            } else if (!bump.isLeavingLeaves) {
                PrimaryButton(
                    text = stringResource(
                        if (selected?.extraSats == 0uL) {
                            R.string.lightning__spark_exit__fee_bump_confirm
                        } else {
                            R.string.lightning__spark_exit__fund_savings
                        },
                    ),
                    onClick = onPay,
                    enabled = selected != null && bump.canPay(selected) && !bump.isPaying,
                    isLoading = bump.isLoading || bump.isPaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitFeeBumpPay")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = SparkExitUiState(
                feeBump = SparkExitFeeBumpUiState(
                    options = persistentListOf(
                        SparkExitFeeBumpOption(FeeRate.FAST, 12uL, 4_200uL, 4_620uL),
                        SparkExitFeeBumpOption(FeeRate.NORMAL, 8uL, 2_400uL, 2_820uL),
                    ),
                    selected = FeeRate.FAST,
                    spendableSats = 100_000uL,
                ),
            ),
        )
    }
}
