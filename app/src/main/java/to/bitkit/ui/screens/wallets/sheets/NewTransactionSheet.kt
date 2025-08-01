package to.bitkit.ui.screens.wallets.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.LocalCurrencyViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.ModalSheetTopPadding
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.CurrencyViewModel

@Composable
fun NewTransactionSheet(
    appViewModel: AppViewModel,
    currencyViewModel: CurrencyViewModel
) {
    val currencies by currencyViewModel.uiState.collectAsState()

    CompositionLocalProvider(
        LocalCurrencyViewModel provides currencyViewModel,
        LocalCurrencies provides currencies,
    ) {
        NewTransactionSheet(
            onDismissRequest = { appViewModel.hideNewTransactionSheet() },
            details = appViewModel.newTransaction,
            onCloseClick = { appViewModel.hideNewTransactionSheet() },
            onDetailClick = {
                appViewModel.hideNewTransactionSheet()
                appViewModel.onClickActivityDetail()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionSheet(
    onDismissRequest: () -> Unit,
    details: NewTransactionSheetDetails,
    onCloseClick: () -> Unit,
    onDetailClick: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(top = ModalSheetTopPadding)
    ) {
        NewTransactionSheetView(
            details = details,
            onCloseClick = onCloseClick,
            onDetailClick = onDetailClick
        )
    }
}

@Composable
fun NewTransactionSheetView(
    details: NewTransactionSheetDetails,
    onCloseClick: () -> Unit,
    onDetailClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("new_transaction_sheet")
    ) {

        if (details.direction == NewTransactionSheetDirection.RECEIVED) {
            Image(
                painter = painterResource(R.drawable.coin_stack_5),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("transaction_received_image")
                    .align(Alignment.BottomEnd)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("transaction_sent_image")
                    .align(Alignment.Center)
            )
        }

        val composition by rememberLottieComposition(
            if (details.type == NewTransactionSheetType.ONCHAIN) {
                LottieCompositionSpec.RawRes(R.raw.confetti_orange)
            } else {
                LottieCompositionSpec.RawRes(R.raw.confetti_purple)
            }
        )
        LottieAnimation(
            composition = composition,
            contentScale = ContentScale.Crop,
            iterations = 100,
            modifier = Modifier
                .fillMaxSize()
                .testTag("confetti_animation")
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .testTag("transaction_content_column")
                .padding(horizontal = 16.dp),
        ) {
            val titleText = when (details.type) {
                NewTransactionSheetType.LIGHTNING -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> stringResource(R.string.wallet__send_sent)
                    else -> stringResource(R.string.wallet__payment_received)
                }

                NewTransactionSheetType.ONCHAIN -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> stringResource(R.string.wallet__send_sent)
                    else -> stringResource(R.string.wallet__payment_received)
                }
            }

            SheetTopBar(titleText)

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDetailClick() }
            ) {
                BalanceHeaderView(
                    sats = details.sats,
                    modifier = Modifier
                        .testTag("balance_header")
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (details.direction == NewTransactionSheetDirection.SENT) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sent_buttons_row")
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.wallet__send_details),
                        onClick = onDetailClick,
                        fullWidth = false,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("details_button")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__close),
                        onClick = onCloseClick,
                        fullWidth = false,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("close_button")
                    )
                }
            } else {
                PrimaryButton(
                    text = localizedRandom(R.string.common__ok_random),
                    onClick = onCloseClick,
                    modifier = Modifier.testTag("ok_button")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.SENT,
                sats = 123456789,
            ),
            onCloseClick = {},
            onDetailClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview2() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.SENT,
                sats = 123456789,
            ),
            onCloseClick = {},
            onDetailClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview3() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 123456789,
            ),
            onCloseClick = {},
            onDetailClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview4() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 123456789,
            ),
            onCloseClick = {},
            onDetailClick = {},
        )
    }
}
