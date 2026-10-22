package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.synonym.bitkitcore.usdtFormatAmount
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.ui.LocalBottomSheetOverlayState
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetOverlayState
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SHEET_INTRO_IMAGE_WIDTH_FRACTION
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.viewmodels.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionSheet(
    appViewModel: AppViewModel,
    bottomSheetOverlayState: BottomSheetOverlayState,
    hideBalance: Boolean,
    modifier: Modifier = Modifier,
) {
    val details by appViewModel.transactionSheet.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalBottomSheetOverlayState provides bottomSheetOverlayState) {
        BottomSheet(
            onDismissRequest = { appViewModel.hideNewTransactionSheet() },
            containerColor = if (details.usdtAmount != null) Colors.Gray7 else null,
            dragHandleTopPadding = if (details.usdtAmount != null) 8.dp else 12.dp,
        ) {
            NewTransactionSheetView(
                details = details,
                hideBalance = hideBalance,
                onCloseClick = { appViewModel.hideNewTransactionSheet() },
                onDetailClick = {
                    appViewModel.onClickActivityDetail()
                },
                modifier = modifier
                    .sheetHeight(isModal = true)
                    .then(
                        if (details.usdtAmount != null) {
                            Modifier.background(Colors.Gray7)
                        } else {
                            Modifier.gradientBackground()
                        }
                    )
                    .navigationBarsPadding()
                    .testTag("new_transaction_sheet")
            )
        }
    }
}

@Composable
fun NewTransactionSheetView(
    details: NewTransactionSheetDetails,
    onCloseClick: () -> Unit,
    onDetailClick: () -> Unit,
    modifier: Modifier = Modifier,
    hideBalance: Boolean = false,
) {
    Box(modifier = modifier) {
        TransactionConfetti(details)

        if (details.direction == NewTransactionSheetDirection.RECEIVED) {
            Image(
                painter = painterResource(
                    if (details.usdtAmount != null) R.drawable.usdt_coins_received else R.drawable.coin_stack_5
                ),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("transaction_received_image")
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(SHEET_INTRO_IMAGE_WIDTH_FRACTION)
                    .heightIn(max = 320.dp)
                    .testTag("transaction_sent_image")
                    .align(Alignment.Center)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .testTag("transaction_content_column")
                .padding(horizontal = 16.dp),
        ) {
            TransactionHeader(details)

            Spacer(modifier = Modifier.height(if (details.usdtAmount != null) 8.dp else 24.dp))

            if (details.usdtAmount != null) {
                UsdtAmountHeader(
                    amount = usdtFormatAmount(details.usdtAmount),
                    displayAmount = usdtOverviewAmount(details.usdtAmount),
                    hideBalance = hideBalance,
                    onClick = {
                        val hasDetails = details.activityId != null || details.paymentHashOrTxId != null ||
                            details.usdtTransferId != null
                        if (!details.isLoadingDetails && hasDetails) onDetailClick()
                    },
                    modifier = Modifier.testTag("ReceivedTransaction")
                )
            } else {
                BalanceHeaderView(
                    sats = details.sats,
                    modifier = Modifier.fillMaxWidth().testTag("ReceivedTransaction")
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TransactionButtons(details, onCloseClick, onDetailClick)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TransactionHeader(details: NewTransactionSheetDetails) {
    val titleText = stringResource(
        when {
            details.usdtAmount != null -> if (details.direction == NewTransactionSheetDirection.SENT) {
                R.string.usdt__payment_sent
            } else {
                R.string.usdt__payment_received
            }
            details.direction == NewTransactionSheetDirection.SENT -> R.string.wallet__send_sent
            details.type == NewTransactionSheetType.LIGHTNING -> R.string.wallet__instant_payment_received
            else -> R.string.wallet__payment_received
        }
    )

    SheetTopBar(
        titleText,
        titleStyle = if (details.usdtAmount != null && details.direction == NewTransactionSheetDirection.RECEIVED) {
            AppTextStyles.Subtitle.copy(letterSpacing = 0.sp, lineHeight = 22.sp)
        } else {
            AppTextStyles.Subtitle
        }
    )
}

private val ConfettiShade = Color(0xFF8E8E93)

@Composable
private fun TransactionConfetti(details: NewTransactionSheetDetails) {
    val composition by rememberLottieComposition(
        if (details.type == NewTransactionSheetType.ONCHAIN) {
            LottieCompositionSpec.RawRes(R.raw.confetti_orange)
        } else {
            LottieCompositionSpec.RawRes(R.raw.confetti_purple)
        }
    )
    LottieAnimation(
        composition = composition,
        dynamicProperties = if (details.usdtAmount != null) {
            rememberLottieDynamicProperties(
                rememberLottieDynamicProperty(
                    property = LottieProperty.COLOR,
                    value = Colors.Usdt.toArgb(),
                    keyPath = arrayOf("**"),
                )
            )
        } else {
            null
        },
        contentScale = ContentScale.Crop,
        iterations = 100,
        modifier = Modifier
            .fillMaxSize()
            .then(if (details.usdtAmount != null) Modifier.background(Colors.Black) else Modifier)
            .testTag("confetti_animation")
    )

    if (details.usdtAmount != null) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(ConfettiShade.copy(alpha = 0.16f), Color.Transparent))
            )
        )
    }
}

@Composable
private fun TransactionButtons(
    details: NewTransactionSheetDetails,
    onCloseClick: () -> Unit,
    onDetailClick: () -> Unit
) {
    val buttonStyle = if (details.usdtAmount != null) {
        AppTextStyles.BodySSB.copy(letterSpacing = 0.sp)
    } else {
        null
    }
    if (details.direction == NewTransactionSheetDirection.SENT || details.usdtTransferId != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sent_buttons_row")
        ) {
            SecondaryButton(
                text = stringResource(R.string.wallet__send_details),
                onClick = onDetailClick,
                textStyle = buttonStyle,
                enabled = !details.isLoadingDetails && (
                    details.activityId != null || details.paymentHashOrTxId != null || details.usdtTransferId != null
                    ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("Details")
            )
            PrimaryButton(
                text = if (details.direction == NewTransactionSheetDirection.RECEIVED) {
                    localizedRandom(R.string.common__ok_random)
                } else {
                    stringResource(R.string.common__close)
                },
                onClick = onCloseClick,
                textStyle = buttonStyle,
                modifier = Modifier
                    .weight(1f)
                    .testTag("Close")
            )
        }
    } else {
        PrimaryButton(
            text = localizedRandom(R.string.common__ok_random),
            onClick = onCloseClick,
            textStyle = buttonStyle,
            modifier = Modifier.testTag("ReceivedTransactionButton")
        )
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
