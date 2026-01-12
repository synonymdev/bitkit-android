package to.bitkit.ui.settings.advanced.sweep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.FeeRate
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SweepState
import to.bitkit.viewmodels.SweepUiState
import to.bitkit.viewmodels.SweepViewModel

@Composable
fun SweepConfirmScreen(
    navController: NavController,
    viewModel: SweepViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadFeeEstimates()
    }

    LaunchedEffect(uiState.selectedFeeRate) {
        if (uiState.selectedFeeRate != null && uiState.sweepState != SweepState.Broadcasting) {
            viewModel.prepareSweep()
        }
    }

    LaunchedEffect(uiState.sweepState) {
        if (uiState.sweepState is SweepState.Success) {
            val amountSats = uiState.sweepResult?.amountSwept?.toLong() ?: 0L
            navController.navigate(Routes.SweepSuccess(amountSats = amountSats)) {
                popUpTo(Routes.Sweep) { inclusive = true }
            }
        }
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectFeeRate = { navController.navigate(Routes.SweepFeeRate) },
        onSwipeComplete = {
            scope.launch {
                viewModel.broadcastSweep()
            }
        },
    )
}

@Composable
private fun Content(
    uiState: SweepUiState,
    onBack: () -> Unit = {},
    onSelectFeeRate: () -> Unit = {},
    onSwipeComplete: () -> Unit = {},
) {
    val isPreparing = uiState.sweepState == SweepState.Preparing
    val isReady = uiState.sweepState == SweepState.Ready

    val displayAmount = if (isReady && uiState.transactionPreview != null) {
        uiState.transactionPreview.amountAfterFees.toLong()
    } else {
        (uiState.sweepableBalances?.totalBalance ?: 0u).toLong()
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.sweep__confirm_nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        VerticalSpacer(16.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            BalanceHeaderView(
                sats = displayAmount,
                modifier = Modifier.alpha(if (isPreparing) 0.5f else 1f)
            )

            VerticalSpacer(24.dp)

            HorizontalDivider(color = Colors.White08)

            VerticalSpacer(24.dp)

            Caption(
                text = stringResource(R.string.sweep__confirm_to_address),
                color = Colors.White64,
            )

            VerticalSpacer(8.dp)

            BodySSB(
                text = uiState.destinationAddress?.ifEmpty { "..." } ?: "...",
                modifier = Modifier.alpha(if (uiState.destinationAddress == null) 0.5f else 1f)
            )

            VerticalSpacer(24.dp)

            HorizontalDivider(color = Colors.White08)

            VerticalSpacer(24.dp)

            val feeRate = FeeRate.fromSpeed(uiState.selectedSpeed)
            val isLoading = isPreparing || uiState.sweepState == SweepState.Broadcasting
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isLoading) {
                            Modifier.clickableAlpha(onClick = onSelectFeeRate)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Column {
                    Caption(
                        text = stringResource(R.string.wallet__send_fee_and_speed),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(feeRate.icon),
                            contentDescription = null,
                            tint = feeRate.color,
                            modifier = Modifier.size(16.dp)
                        )
                        BodySSB(
                            text = if (uiState.estimatedFee > 0u && !isPreparing) {
                                " ${stringResource(feeRate.title)} (${
                                    stringResource(
                                        R.string.sweep__balance_format,
                                        uiState.estimatedFee.toLong().formatToModernDisplay(),
                                    )
                                })"
                            } else {
                                " ${stringResource(feeRate.title)}"
                            },
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Caption(
                        text = stringResource(R.string.wallet__send_confirming_in),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = Colors.Brand,
                            modifier = Modifier.size(16.dp)
                        )
                        BodySSB(text = " ${stringResource(feeRate.description)}")
                    }
                }
            }

            VerticalSpacer(24.dp)

            HorizontalDivider(color = Colors.White08)

            if (uiState.errorMessage != null) {
                VerticalSpacer(16.dp)
                BodyM(
                    text = uiState.errorMessage,
                    color = Colors.Red,
                )
            }

            FillHeight()

            BottomActions(
                uiState = uiState,
                onSwipeComplete = onSwipeComplete,
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun BottomActions(
    uiState: SweepUiState,
    onSwipeComplete: () -> Unit,
) {
    when (uiState.sweepState) {
        SweepState.Idle, SweepState.Ready -> {
            if (uiState.destinationAddress != null && uiState.transactionPreview != null) {
                SwipeToConfirm(
                    text = stringResource(R.string.sweep__confirm_swipe),
                    onConfirm = onSwipeComplete,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        SweepState.Preparing -> LoadingIndicator(stringResource(R.string.sweep__confirm_preparing))
        SweepState.Broadcasting -> LoadingIndicator(stringResource(R.string.sweep__confirm_broadcasting))
        is SweepState.Error -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                BodyM(
                    text = uiState.sweepState.message,
                    color = Colors.Red,
                )
                VerticalSpacer(16.dp)
                SwipeToConfirm(
                    text = stringResource(R.string.sweep__confirm_retry),
                    onConfirm = onSwipeComplete,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is SweepState.Success -> Unit
    }
}

@Composable
private fun LoadingIndicator(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = Colors.White32,
            strokeWidth = 3.dp,
        )
        VerticalSpacer(32.dp)
        Caption(
            text = text,
            color = Colors.White64,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = SweepUiState(
                destinationAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                selectedSpeed = TransactionSpeed.Medium,
            ),
        )
    }
}
