package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synonym.bitkitcore.Activity
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.ModalBottomSheetHandle
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.ModalSheetTopPadding
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoostTransactionSheet(
    modifier: Modifier = Modifier,
    viewModel: BoostTransactionViewModel = hiltViewModel(),
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
    onMaxFee: () -> Unit,
    onMinFee: () -> Unit,
    onDismiss: () -> Unit,
    item: Activity.Onchain,
) {
    val haptic = LocalHapticFeedback.current

    // Setup activity when component is first created
    LaunchedEffect(item) {
        viewModel.setupActivity(item)
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.boostTransactionEffect.collect { event ->
            when (event) {
                BoostTransactionEffects.OnBoostFailed -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFailure()
                }

                BoostTransactionEffects.OnBoostSuccess -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSuccess()
                }

                BoostTransactionEffects.OnMaxFee -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMaxFee()
                }

                BoostTransactionEffects.OnMinFee -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMinFee()
                }
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = Colors.Black,
        dragHandle = { ModalBottomSheetHandle() },
        modifier = Modifier
            .padding(top = ModalSheetTopPadding)
            .testTag(BoostTransactionTestTags.BOOST_TRANSACTION_SHEET)
    ) {
        BoostTransactionContent(
            modifier = modifier,
            onClickEdit = viewModel::onClickEdit,
            onClickUseSuggestedFee = viewModel::onClickUseSuggestedFee,
            onChangeAmount = viewModel::onChangeAmount,
            onSwipe = viewModel::onConfirmBoost,
            uiState = uiState
        )
    }
}

@Composable
fun BoostTransactionContent(
    modifier: Modifier = Modifier,
    uiState: BoostTransactionUiState,
    onClickEdit: () -> Unit,
    onClickUseSuggestedFee: () -> Unit,
    onChangeAmount: (Boolean) -> Unit,
    onSwipe: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .padding(horizontal = 16.dp)
            .testTag(BoostTransactionTestTags.BOOST_TRANSACTION_CONTENT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__boost_title),
            modifier = Modifier.testTag(BoostTransactionTestTags.SHEET_TOP_BAR)
        )

        val bodyText = if (uiState.isDefaultMode) {
            R.string.wallet__boost_fee_recomended
        } else {
            R.string.wallet__boost_fee_custom
        }

        BodyS(
            text = stringResource(bodyText),
            color = Colors.White64,
            modifier = Modifier.testTag(BoostTransactionTestTags.DESCRIPTION_TEXT)
        )

        VerticalSpacer(24.dp)

        when {
            uiState.loading -> {
                LoadingState()
            }

            uiState.isDefaultMode -> {
                DefaultModeContent(
                    uiState = uiState,
                    onClickEdit = onClickEdit,
                    onSwipe = onSwipe
                )
            }

            else -> {
                CustomModeContent(
                    uiState = uiState,
                    onChangeAmount = onChangeAmount,
                    onClickUseSuggestedFee = onClickUseSuggestedFee,
                    onSwipe = onSwipe
                )
            }
        }

        VerticalSpacer(8.dp)
    }
}

@Composable
private fun LoadingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        CircularProgressIndicator(
            color = Colors.Yellow,
            modifier = Modifier
                .size(32.dp)
                .testTag(BoostTransactionTestTags.LOADING_INDICATOR)
        )
    }
}

@Composable
private fun DefaultModeContent(
    uiState: BoostTransactionUiState,
    onClickEdit: () -> Unit,
    onSwipe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClickEdit() }
            .testTag(BoostTransactionTestTags.EDIT_FEE_ROW)
            .semantics {
                contentDescription = "Edit fee settings"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_timer_alt_yellow),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .testTag(BoostTransactionTestTags.TIMER_ICON)
        )

        HorizontalSpacer(16.dp)

        Column(modifier = Modifier.weight(1f)) {
            BodyMSB(
                text = stringResource(R.string.wallet__boost),
                color = Colors.White,
                modifier = Modifier.testTag(BoostTransactionTestTags.BOOST_TITLE)
            )
            BodySSB(
                text = uiState.estimateTime,
                color = Colors.White64,
                modifier = Modifier.testTag(BoostTransactionTestTags.ESTIMATE_TIME)
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val feeText = rememberMoneyText(sats = uiState.totalFeeSats.toLong())
                    ?.withAccent(defaultColor = Colors.White)
                    ?.toString()
                    .orEmpty()

                BodyMSB(
                    text = feeText,
                    color = Colors.White,
                    modifier = Modifier.testTag(BoostTransactionTestTags.TOTAL_FEE_PRIMARY)
                )

                Icon(
                    painter = painterResource(R.drawable.ic_pencil_simple),
                    tint = Colors.White,
                    contentDescription = stringResource(R.string.common__edit),
                    modifier = Modifier
                        .size(16.dp)
                        .testTag(BoostTransactionTestTags.EDIT_FEE_ICON)
                )
            }

            val feeTextSecondary = rememberMoneyText(
                sats = uiState.totalFeeSats.toLong(),
                reversed = true
            )?.withAccent(defaultColor = Colors.White64)
                ?.toString()
                .orEmpty()

            BodySSB(
                text = feeTextSecondary,
                color = Colors.White64,
                modifier = Modifier.testTag(BoostTransactionTestTags.TOTAL_FEE_SECONDARY)
            )
        }
    }

    VerticalSpacer(68.dp)

    SwipeToConfirm(
        text = stringResource(R.string.wallet__boost_swipe),
        color = Colors.Yellow,
        endIcon = R.drawable.ic_timer_alt_yellow,
        onConfirm = onSwipe,
        loading = uiState.boosting,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(BoostTransactionTestTags.SWIPE_TO_CONFIRM)
    )
}

@Composable
private fun CustomModeContent(
    uiState: BoostTransactionUiState,
    onChangeAmount: (Boolean) -> Unit,
    onClickUseSuggestedFee: () -> Unit,
    onSwipe: () -> Unit
) {
    Column(
        modifier = Modifier.testTag(BoostTransactionTestTags.CUSTOM_MODE_CONTENT),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuantityButton(
                icon = painterResource(R.drawable.ic_minus),
                iconColor = Colors.Red,
                backgroundColor = Colors.Red16,
                enabled = uiState.decreaseEnabled,
                onClick = { onChangeAmount(false) },
                contentDescription = "Reduce fee",
                modifier = Modifier.testTag(BoostTransactionTestTags.DECREASE_FEE_BUTTON)
            )

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val rateText = rememberMoneyText(sats = uiState.feeRate.toLong())
                    ?.withAccent(defaultColor = Colors.White)
                    ?.toString()
                    .orEmpty()

                BodyMSB(
                    text = "$rateText/vbyte ($BITCOIN_SYMBOL ${uiState.totalFeeSats})",
                    color = Colors.White,
                    modifier = Modifier.testTag(BoostTransactionTestTags.FEE_RATE_TEXT)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val feeTextSecondary = rememberMoneyText(
                        sats = uiState.totalFeeSats.toLong(),
                        reversed = true
                    )?.withAccent(defaultColor = Colors.White64)
                        ?.toString()
                        .orEmpty()

                    BodySSB(
                        text = feeTextSecondary,
                        color = Colors.White64,
                        modifier = Modifier.testTag(BoostTransactionTestTags.TOTAL_FEE_SECONDARY)
                    )

                    BodySSB(
                        text = uiState.estimateTime,
                        color = Colors.White64,
                        modifier = Modifier.testTag(BoostTransactionTestTags.ESTIMATE_TIME)
                    )
                }
            }

            QuantityButton(
                icon = painterResource(R.drawable.ic_plus),
                iconColor = Colors.Green,
                backgroundColor = Colors.Green16,
                enabled = uiState.increaseEnabled,
                onClick = { onChangeAmount(true) },
                contentDescription = "Increase fee",
                modifier = Modifier.testTag(BoostTransactionTestTags.INCREASE_FEE_BUTTON)
            )
        }

        VerticalSpacer(16.dp)

        PrimaryButton(
            text = stringResource(R.string.wallet__boost_recomended_button),
            fullWidth = false,
            onClick = onClickUseSuggestedFee,
            size = ButtonSize.Small,
            modifier = Modifier.testTag(BoostTransactionTestTags.USE_SUGGESTED_FEE_BUTTON)
        )

        VerticalSpacer(16.dp)

        SwipeToConfirm(
            text = stringResource(R.string.wallet__boost_swipe),
            color = Colors.Yellow,
            endIcon = R.drawable.ic_timer_alt_yellow,
            onConfirm = onSwipe,
            loading = uiState.boosting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BoostTransactionTestTags.SWIPE_TO_CONFIRM)
        )
    }
}

@Composable
fun QuantityButton(
    icon: Painter,
    iconColor: Color,
    backgroundColor: Color,
    enabled: Boolean = true,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    IconButton(
        onClick = {
            if (enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        },
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = backgroundColor,
            contentColor = iconColor,
            disabledContainerColor = Colors.Gray3,
            disabledContentColor = Colors.Gray1
        ),
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = if (enabled) iconColor else Colors.Gray1,
            modifier = Modifier.size(16.dp),
        )
    }
}

object BoostTransactionTestTags {
    const val BOOST_TRANSACTION_SHEET = "boost_transaction_sheet"
    const val BOOST_TRANSACTION_CONTENT = "boost_transaction_content"
    const val SHEET_TOP_BAR = "sheet_top_bar"
    const val DESCRIPTION_TEXT = "description_text"
    const val LOADING_INDICATOR = "loading_indicator"
    const val DEFAULT_MODE_CONTENT = "default_mode_content"
    const val CUSTOM_MODE_CONTENT = "custom_mode_content"
    const val EDIT_FEE_ROW = "edit_fee_row"
    const val EDIT_FEE_ICON = "edit_fee_icon"
    const val TIMER_ICON = "timer_icon"
    const val BOOST_TITLE = "boost_title"
    const val ESTIMATE_TIME = "estimate_time"
    const val TOTAL_FEE_PRIMARY = "total_fee_primary"
    const val TOTAL_FEE_SECONDARY = "total_fee_secondary"
    const val SWIPE_TO_CONFIRM = "swipe_to_confirm"
    const val DECREASE_FEE_BUTTON = "decrease_fee_button"
    const val INCREASE_FEE_BUTTON = "increase_fee_button"
    const val FEE_RATE_TEXT = "fee_rate_text"
    const val USE_SUGGESTED_FEE_BUTTON = "use_suggested_fee_button"
}

// Preview Composables
@Preview(showBackground = true, name = "Default mode")
@Composable
private fun PreviewDefaultMode() {
    AppThemeSurface {
        BoostTransactionContent(
            onClickEdit = {},
            onClickUseSuggestedFee = {},
            onChangeAmount = {},
            onSwipe = {},
            uiState = BoostTransactionUiState(
                totalFeeSats = 4250UL,
                estimateTime = "±10-20 minutes",
                loading = false,
                isDefaultMode = true,
                feeRate = 4UL,
            )
        )
    }
}

@Preview(showBackground = true, name = "Custom mode")
@Composable
private fun PreviewCustomMode() {
    AppThemeSurface {
        BoostTransactionContent(
            onClickEdit = {},
            onClickUseSuggestedFee = {},
            onChangeAmount = {},
            onSwipe = {},
            uiState = BoostTransactionUiState(
                totalFeeSats = 4250UL,
                estimateTime = "±10-20 minutes",
                loading = false,
                isDefaultMode = false,
                feeRate = 4UL,
            )
        )
    }
}

@Preview(showBackground = true, name = "Loading state")
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        BoostTransactionContent(
            onClickEdit = {},
            onClickUseSuggestedFee = {},
            onChangeAmount = {},
            onSwipe = {},
            uiState = BoostTransactionUiState(
                totalFeeSats = 4250UL,
                estimateTime = "±10-20 minutes",
                loading = true,
                isDefaultMode = false,
                feeRate = 4UL,
            )
        )
    }
}
