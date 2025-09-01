package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import to.bitkit.ui.LocalSettingsViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionSheet(
    appViewModel: AppViewModel,
    currencyViewModel: CurrencyViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val currencies by currencyViewModel.uiState.collectAsState()
    val newTransaction by appViewModel.newTransaction.collectAsState()

    CompositionLocalProvider(
        LocalCurrencyViewModel provides currencyViewModel,
        LocalSettingsViewModel provides settingsViewModel,
        LocalCurrencies provides currencies,
    ) {
        BottomSheet(
            onDismissRequest = { appViewModel.hideNewTransactionSheet() },
        ) {
            NewTransactionSheetView(
                details = newTransaction,
                onCloseClick = { appViewModel.hideNewTransactionSheet() },
                onDetailClick = {
                    appViewModel.onClickActivityDetail()
                },
            )
        }
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
            .semantics { testTagsAsResourceId = true }
            .sheetHeight(isModal = true)
            .gradientBackground()
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

            BalanceHeaderView(
                sats = details.sats,
                onClick = { onDetailClick },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(
                        if (details.direction == NewTransactionSheetDirection.SENT) {
                            "SendSuccess"
                        } else {
                            "ReceivedTransaction"
                        }
                    )
            )

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
                        enabled = details.paymentHashOrTxId != null,
                        isLoading = details.isLoadingDetails,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Details")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__close),
                        onClick = onCloseClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Close")
                    )
                }
            } else {
                PrimaryButton(
                    text = localizedRandom(R.string.common__ok_random),
                    onClick = onCloseClick,
                    modifier = Modifier.testTag("ReceivedTransactionButton")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
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
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        BottomSheetPreview {
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
}

@Preview(showSystemUi = true)
@Composable
private fun Preview3() {
    AppThemeSurface {
        BottomSheetPreview {
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
}

@Preview(showSystemUi = true)
@Composable
private fun Preview4() {
    AppThemeSurface {
        BottomSheetPreview {
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
}
