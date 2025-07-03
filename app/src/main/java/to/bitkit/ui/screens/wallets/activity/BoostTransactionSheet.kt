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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    //TODO Handle CPFP too
    modifier: Modifier = Modifier,
    viewModel: BoostTransactionViewModel = hiltViewModel(),
    onSuccess: () -> Unit,
    onFailure: () -> Unit,
    onMaxFee: () -> Unit,
    onMinFee: () -> Unit,
    onDismiss: () -> Unit,
    item: Activity.Onchain,
) {
    LaunchedEffect(Unit) {
        viewModel.setupActivity(item)
    }

    LaunchedEffect(Unit) {
        viewModel.boostTransactionEffect.collect { event ->
            when (event) {
                BoostTransactionEffects.OnBoostFailed -> onFailure()
                BoostTransactionEffects.OnBoostSuccess -> onSuccess()
                BoostTransactionEffects.onMaxFee -> onMaxFee()
                BoostTransactionEffects.onMinFee -> onMinFee()
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
        modifier = Modifier.padding(top = ModalSheetTopPadding)
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
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__boost_title))

        val bodyText =
            if (uiState.isDefaultMode) R.string.wallet__boost_fee_recomended else R.string.wallet__boost_fee_custom

        BodyS(text = stringResource(bodyText), color = Colors.White64)

        VerticalSpacer(24.dp)

        when {
            uiState.loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            uiState.isDefaultMode -> {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_timer_alt_yellow),
                        contentDescription = null
                    )

                    HorizontalSpacer(16.dp)

                    Column(modifier = Modifier.weight(1f)) {
                        BodyMSB(text = stringResource(R.string.wallet__boost), color = Colors.White)
                        BodySSB(text = uiState.estimateTime, color = Colors.White64)
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable { onClickEdit() },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            BodyMSB(
                                text = rememberMoneyText(sats = uiState.totalFeeSats.toLong())
                                    .orEmpty()
                                    .withAccent(defaultColor = Colors.White).toString(),
                                color = Colors.White
                            )

                            Icon(
                                painter = painterResource(R.drawable.ic_pencil_simple),
                                tint = Colors.White,
                                contentDescription = stringResource(R.string.common__edit),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        BodySSB(
                            text = rememberMoneyText(
                                sats = uiState.totalFeeSats.toLong(),
                                reversed = true
                            ).orEmpty().withAccent(defaultColor = Colors.White64).toString(),
                            color = Colors.White64
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
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    QuantityIcon(
                        icon = painterResource(R.drawable.ic_minus),
                        iconColor = Colors.Red,
                        backgroundColor = Colors.Red16,
                        enable = uiState.decreaseEnabled,
                        onClick = { onChangeAmount(false) },
                        contentDescription = "Reduce fee",
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        BodyMSB(
                            text = rememberMoneyText(sats = uiState.feeRate.toLong())
                                .orEmpty()
                                .withAccent(defaultColor = Colors.White).toString() + "/vbyte ($BITCOIN_SYMBOL ${uiState.totalFeeSats})" ,
                            color = Colors.White
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BodySSB(
                                text = rememberMoneyText(
                                    sats = uiState.totalFeeSats.toLong(),
                                    reversed = true
                                ).orEmpty().withAccent(defaultColor = Colors.White64).toString(),
                                color = Colors.White64
                            )

                            BodySSB(
                                text = uiState.estimateTime,
                                color = Colors.White64
                            )
                        }
                    }

                    QuantityIcon(
                        icon = painterResource(R.drawable.ic_plus),
                        iconColor = Colors.Green,
                        backgroundColor = Colors.Green16,
                        enable = uiState.increaseEnabled,
                        onClick = { onChangeAmount(true) },
                        contentDescription = "Increase fee",
                    )
                }

                VerticalSpacer(16.dp)

                PrimaryButton(
                    text = stringResource(R.string.wallet__boost_recomended_button),
                    fullWidth = false,
                    onClick = onClickUseSuggestedFee,
                    size = ButtonSize.Small
                )

                VerticalSpacer(16.dp)

                SwipeToConfirm(
                    text = stringResource(R.string.wallet__boost_swipe),
                    color = Colors.Yellow,
                    endIcon = R.drawable.ic_timer_alt_yellow,
                    onConfirm = onSwipe,
                    loading = uiState.boosting,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        VerticalSpacer(8.dp)
    }
}

@Composable
fun QuantityIcon(
    icon: Painter,
    iconColor: Color,
    backgroundColor: Color,
    enable: Boolean = true,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enable,
        colors = IconButtonDefaults.iconButtonColors().copy(
            containerColor = backgroundColor,
            contentColor = iconColor,
            disabledContainerColor = Colors.Gray3,
            disabledContentColor = Colors.Gray1
        ),
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = if (enable) iconColor else Colors.Gray1,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Preview(showBackground = true, name = "Edit mode")
@Composable
private fun Preview() {
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

@Preview(showBackground = true)
@Composable
private fun Preview2() {
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

@Preview(showBackground = true)
@Composable
private fun Preview3() {
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
