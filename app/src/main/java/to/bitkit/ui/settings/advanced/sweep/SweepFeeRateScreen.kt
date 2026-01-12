package to.bitkit.ui.settings.advanced.sweep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Defaults
import to.bitkit.models.FeeRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SweepUiState
import to.bitkit.viewmodels.SweepViewModel

@Composable
fun SweepFeeRateScreen(
    navController: NavController,
    viewModel: SweepViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectSpeed = { speed ->
            viewModel.setFeeRate(speed)
            navController.popBackStack()
        },
        onCustom = { navController.navigate(Routes.SweepFeeCustom) },
    )
}

@Composable
private fun Content(
    uiState: SweepUiState,
    onBack: () -> Unit = {},
    onSelectSpeed: (TransactionSpeed) -> Unit = {},
    onCustom: () -> Unit = {},
) {
    val feeRates = uiState.feeRates
    val estimatedVsize = uiState.transactionPreview?.estimatedVsize ?: 0u
    val totalBalance = uiState.sweepableBalances?.totalBalance ?: 0u

    fun getFee(speed: TransactionSpeed): Long {
        val feeRate: UInt = when (speed) {
            is TransactionSpeed.Custom -> speed.satsPerVByte
            else -> feeRates?.let { speed.getFeeRate(it) } ?: 0u
        }
        return (feeRate.toULong() * estimatedVsize).toLong()
    }

    fun isDisabled(speed: TransactionSpeed): Boolean {
        val fee = getFee(speed).toULong()
        return fee + Defaults.dustLimit > totalBalance
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.sweep__fee_nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            SectionHeader(
                title = stringResource(R.string.wallet__send_fee_and_speed),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (feeRates == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Colors.White32,
                    )
                }
                return@ScreenColumn
            }

            FeeItem(
                feeRate = FeeRate.FAST,
                sats = getFee(TransactionSpeed.Fast),
                isSelected = uiState.selectedSpeed is TransactionSpeed.Fast,
                isDisabled = isDisabled(TransactionSpeed.Fast),
                onClick = { onSelectSpeed(TransactionSpeed.Fast) },
            )

            FeeItem(
                feeRate = FeeRate.NORMAL,
                sats = getFee(TransactionSpeed.Medium),
                isSelected = uiState.selectedSpeed is TransactionSpeed.Medium,
                isDisabled = isDisabled(TransactionSpeed.Medium),
                onClick = { onSelectSpeed(TransactionSpeed.Medium) },
            )

            FeeItem(
                feeRate = FeeRate.SLOW,
                sats = getFee(TransactionSpeed.Slow),
                isSelected = uiState.selectedSpeed is TransactionSpeed.Slow,
                isDisabled = isDisabled(TransactionSpeed.Slow),
                onClick = { onSelectSpeed(TransactionSpeed.Slow) },
            )

            val customRate = (uiState.selectedSpeed as? TransactionSpeed.Custom)?.satsPerVByte ?: 0u
            FeeItem(
                feeRate = FeeRate.CUSTOM,
                sats = if (customRate > 0u) getFee(TransactionSpeed.Custom(customRate)) else 0L,
                isSelected = uiState.selectedSpeed is TransactionSpeed.Custom,
                isDisabled = false,
                onClick = onCustom,
            )

            FillHeight(min = 16.dp)

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onBack,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            VerticalSpacer(16.dp)
        }
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
                    MoneySSB(sats, unit = unit.not(), color = accent, accent = accent, showSymbol = true)
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = SweepUiState(
                selectedSpeed = TransactionSpeed.Medium,
            ),
        )
    }
}
