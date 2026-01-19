package to.bitkit.ui.settings.advanced.sweep

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.CheckState
import to.bitkit.viewmodels.SweepUiState
import to.bitkit.viewmodels.SweepViewModel
import to.bitkit.viewmodels.SweepableBalances

@Composable
fun SweepSettingsScreen(
    navController: NavController,
    viewModel: SweepViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.reset()
        viewModel.checkBalance()
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSweepToWallet = { navController.navigate(Routes.SweepConfirm) },
        onRetry = { viewModel.checkBalance() },
    )
}

@Composable
private fun Content(
    uiState: SweepUiState,
    onBack: () -> Unit = {},
    onSweepToWallet: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val title = when (uiState.checkState) {
        is CheckState.Found -> stringResource(R.string.sweep__found_title)
        is CheckState.NoFunds -> stringResource(R.string.sweep__no_funds_title)
        else -> stringResource(R.string.sweep__nav_title)
    }

    ScreenColumn {
        AppTopBar(
            titleText = title,
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        VerticalSpacer(30.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            when (uiState.checkState) {
                CheckState.Idle, CheckState.Checking -> LoadingView()
                is CheckState.Found -> FoundFundsView(
                    balances = uiState.sweepableBalances ?: SweepableBalances(),
                    onSweepToWallet = onSweepToWallet,
                )
                CheckState.NoFunds -> NoFundsView(onBack = onBack)
                is CheckState.Error -> ErrorView(
                    message = uiState.checkState.message,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        BodyM(
            text = stringResource(R.string.sweep__checking_description),
            color = Colors.White64,
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.magnifying_glass),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(311.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Colors.White32,
                strokeWidth = 3.dp,
            )

            VerticalSpacer(16.dp)

            Caption(
                text = stringResource(R.string.sweep__checking_loading),
                color = Colors.White64,
            )
        }

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun FoundFundsView(
    balances: SweepableBalances,
    onSweepToWallet: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        BodyM(
            text = stringResource(R.string.sweep__found_description),
            color = Colors.White64,
        )

        VerticalSpacer(24.dp)

        Caption(
            text = stringResource(R.string.sweep__found_label),
            color = Colors.White64,
        )

        VerticalSpacer(16.dp)

        if (balances.legacyBalance > 0u) {
            FundRow(
                title = stringResource(R.string.sweep__legacy_title),
                utxoCount = balances.legacyUtxosCount,
                balance = balances.legacyBalance,
            )
        }

        if (balances.p2shBalance > 0u) {
            FundRow(
                title = stringResource(R.string.sweep__segwit_title),
                utxoCount = balances.p2shUtxosCount,
                balance = balances.p2shBalance,
            )
        }

        if (balances.taprootBalance > 0u) {
            FundRow(
                title = stringResource(R.string.sweep__taproot_title),
                utxoCount = balances.taprootUtxosCount,
                balance = balances.taprootBalance,
            )
        }

        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Title(text = stringResource(R.string.sweep__total))
            rememberMoneyText(sats = balances.totalBalance.toLong(), showSymbol = true)?.let {
                Text(text = it.withAccent(accentColor = Colors.White), style = AppTextStyles.Title)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(R.string.sweep__to_wallet),
            onClick = onSweepToWallet,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("SweepToWalletButton")
        )

        VerticalSpacer(16.dp)
    }
}

@Composable
private fun FundRow(
    title: String,
    utxoCount: UInt,
    balance: ULong,
) {
    val utxoLabel = if (utxoCount == 1u) {
        stringResource(R.string.sweep__utxo_format, title, utxoCount.toInt())
    } else {
        stringResource(R.string.sweep__utxos_format, title, utxoCount.toInt())
    }
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            BodySSB(text = utxoLabel)
            MoneySSB(sats = balance.toLong(), showSymbol = true)
        }
        HorizontalDivider(color = Colors.White08)
    }
}

@Composable
private fun NoFundsView(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        BodyM(
            text = stringResource(R.string.sweep__no_funds_description),
            color = Colors.White64,
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.check),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(311.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(16.dp)
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Icon(
            painter = painterResource(id = R.drawable.ic_warning),
            contentDescription = null,
            tint = Colors.Red,
            modifier = Modifier.size(64.dp)
        )

        VerticalSpacer(24.dp)

        BodySSB(text = stringResource(R.string.sweep__error_title))

        VerticalSpacer(8.dp)

        BodyM(
            text = message,
            color = Colors.White64,
        )

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(R.string.common__retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(16.dp)
    }
}

@Preview
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        Content(
            uiState = SweepUiState(checkState = CheckState.Checking),
        )
    }
}

@Preview
@Composable
private fun PreviewFound() {
    AppThemeSurface {
        Content(
            uiState = SweepUiState(
                checkState = CheckState.Found(100000u),
                sweepableBalances = SweepableBalances(
                    legacyBalance = 50000u,
                    legacyUtxosCount = 2u,
                    p2shBalance = 30000u,
                    p2shUtxosCount = 1u,
                    taprootBalance = 20000u,
                    taprootUtxosCount = 1u,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun PreviewNoFunds() {
    AppThemeSurface {
        Content(
            uiState = SweepUiState(checkState = CheckState.NoFunds),
        )
    }
}
