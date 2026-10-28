package to.bitkit.ui.screens.wallets.receive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.WalletState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AddTagButton
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("ViewModelForwarding")
@Composable
fun EditInvoiceScreen(
    amountInputViewModel: AmountInputViewModel,
    walletUiState: WalletState,
    sourceTab: ReceiveTab,
    updateInvoice: (ULong?) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onDescriptionUpdate: (String) -> Unit,
    showPaymentRequestButton: Boolean,
    onClickPaymentRequest: (amountSats: ULong, note: String) -> Unit,
    onBack: () -> Unit,
    onRequestLightning: (ULong) -> Unit,
    onchainOnly: Boolean = false,
    updateOnchainInvoice: (ULong?) -> Unit = {},
    currencies: CurrencyState = LocalCurrencies.current,
) {
    var keyboardVisible by remember { mutableStateOf(false) }
    val amountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    EditInvoiceContent(
        amountInputViewModel = amountInputViewModel,
        noteText = walletUiState.bip21Description,
        currencies = currencies,
        tags = walletUiState.selectedTags,
        onBack = onBack,
        onTextChanged = onDescriptionUpdate,
        keyboardVisible = keyboardVisible,
        onClickBalance = {
            if (keyboardVisible) {
                amountInputViewModel.switchUnit(currencies)
            } else {
                keyboardVisible = true
            }
        },
        onContinueKeyboard = { keyboardVisible = false },
        onContinueGeneral = {
            val amountSats = amountInputUiState.sats.toULong()
            if (sourceTab == ReceiveTab.SPENDING) {
                onRequestLightning(amountSats)
            } else {
                updateInvoice(amountSats)
                onBack()
            }
        },
        onContinueOnchain = { amountSats ->
            updateOnchainInvoice(amountSats)
            onBack()
        },
        onClickAddTag = onClickAddTag,
        onClickTag = onClickTag,
        showPaymentRequestButton = showPaymentRequestButton,
        onClickPaymentRequest = {
            onClickPaymentRequest(amountInputUiState.sats.toULong(), walletUiState.bip21Description)
        },
        onchainOnly = onchainOnly,
    )
}

@Suppress("ViewModelForwarding")
@Composable
fun EditInvoiceContent(
    amountInputViewModel: AmountInputViewModel,
    noteText: String,
    modifier: Modifier = Modifier,
    onchainOnly: Boolean = false,
    keyboardVisible: Boolean,
    tags: ImmutableList<String>,
    onBack: () -> Unit,
    onContinueKeyboard: () -> Unit,
    onClickBalance: () -> Unit,
    onContinueGeneral: () -> Unit,
    onContinueOnchain: (ULong) -> Unit = {},
    onClickAddTag: () -> Unit,
    onTextChanged: (String) -> Unit,
    onClickTag: (String) -> Unit,
    showPaymentRequestButton: Boolean = false,
    onClickPaymentRequest: () -> Unit = {},
    isLoading: Boolean = false,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    val amountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    if (keyboardVisible) {
        EditInvoiceAmountContent(amountInputViewModel, currencies, onBack, onClickBalance, onContinueKeyboard, modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("edit_invoice_screen")
        ) {
            SheetTopBar(
                titleText = stringResource(R.string.wallet__receive_specify),
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .testTag("ReceiveAmount")
            ) {
                VerticalSpacer(16.dp)

                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    showEditButton = !keyboardVisible,
                    onClick = onClickBalance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPadTextField")
                )

                // Animated visibility for note section
                AnimatedVisibility(
                    visible = !keyboardVisible,
                    enter = fadeIn(animationSpec = tween()),
                    exit = fadeOut(animationSpec = tween())
                ) {
                    Column {
                        VerticalSpacer(44.dp)
                        Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)
                        VerticalSpacer(8.dp)

                        TextField(
                            placeholder = {
                                BodySSB(
                                    text = stringResource(R.string.wallet__receive_note_placeholder),
                                    color = Colors.White64
                                )
                            },
                            value = noteText,
                            onValueChange = onTextChanged,
                            textStyle = AppTextStyles.BodySSB,
                            minLines = 4,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                imeAction = ImeAction.Done
                            ),
                            colors = AppTextFieldDefaults.semiTransparent.copy(
                                focusedContainerColor = Colors.White06,
                                unfocusedContainerColor = Colors.White06,
                                errorContainerColor = Colors.White06,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ReceiveNote")
                        )

                        VerticalSpacer(16.dp)
                        if (!onchainOnly) {
                            Caption13Up(text = stringResource(R.string.wallet__tags), color = Colors.White64)
                            VerticalSpacer(8.dp)

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tags.forEach { tagText ->
                                    TagButton(
                                        text = tagText,
                                        displayIconClose = true,
                                        onClick = { onClickTag(tagText) },
                                    )
                                }
                                AddTagButton(
                                    onClick = onClickAddTag,
                                    modifier = Modifier.testTag("TagsAdd")
                                )
                            }
                        }

                        FillHeight()

                        if (showPaymentRequestButton) {
                            SecondaryButton(
                                text = stringResource(R.string.wallet__payment_request_send),
                                onClick = onClickPaymentRequest,
                                enabled = amountInputUiState.sats > 0,
                                modifier = Modifier.testTag("PaymentRequestSendButton"),
                            )
                            VerticalSpacer(12.dp)
                        }

                        PrimaryButton(
                            text = stringResource(R.string.wallet__receive_show_qr),
                            onClick = {
                                if (onchainOnly) {
                                    onContinueOnchain(amountInputViewModel.uiState.value.sats.toULong())
                                } else {
                                    onContinueGeneral()
                                }
                            },
                            isLoading = isLoading,
                            modifier = Modifier.testTag("ShowQrReceive")
                        )

                        VerticalSpacer(16.dp)
                    }
                }
            }
        }
    }
}

@Suppress("ViewModelForwarding")
@Composable
private fun EditInvoiceAmountContent(
    amountInputViewModel: AmountInputViewModel,
    currencies: CurrencyState,
    onBack: () -> Unit,
    onClickBalance: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().gradientBackground().navigationBarsPadding().testTag("edit_invoice_screen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__receive_specify), onBack = onBack)
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("ReceiveAmount")) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                VerticalSpacer(8.dp)
                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    onClick = onClickBalance,
                    modifier = Modifier.fillMaxWidth().testTag("ReceiveNumberPadTextField")
                )
                VerticalSpacer(12.dp)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    UnitButton(
                        onClick = { amountInputViewModel.switchUnit(currencies) },
                        modifier = Modifier.height(28.dp).testTag("ReceiveNumberPadUnit")
                    )
                }
                VerticalSpacer(8.dp)
            }
            HorizontalDivider()
            BoxWithConstraints(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier.weight(1.5f).fillMaxWidth().testTag("ReceiveNumberPad")
            ) {
                NumberPad(
                    viewModel = amountInputViewModel,
                    currencies = currencies,
                    modifier = Modifier
                        .height(maxHeight.coerceAtMost(300.dp))
                        .fillMaxWidth()
                        .testTag("ReceiveNumberField")
                )
            }
            VerticalSpacer(12.dp)
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                modifier = Modifier.testTag("ReceiveNumberPadSubmit")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            EditInvoiceContent(
                amountInputViewModel = previewAmountInputViewModel(),
                noteText = "",
                onBack = {},
                onTextChanged = {},
                keyboardVisible = false,
                onClickBalance = {},
                onContinueGeneral = {},
                onContinueKeyboard = {},
                tags = persistentListOf(),
                onClickAddTag = {},
                onClickTag = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithTags() {
    AppThemeSurface {
        BottomSheetPreview {
            EditInvoiceContent(
                amountInputViewModel = previewAmountInputViewModel(),
                noteText = "Note text",
                onBack = {},
                onTextChanged = {},
                keyboardVisible = false,
                onClickBalance = {},
                onContinueGeneral = {},
                onContinueKeyboard = {},
                tags = persistentListOf("Team", "Dinner", "Home", "Work"),
                onClickAddTag = {},
                onClickTag = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithKeyboard() {
    AppThemeSurface {
        BottomSheetPreview {
            EditInvoiceContent(
                amountInputViewModel = previewAmountInputViewModel(),
                noteText = "Note text",
                onBack = {},
                onTextChanged = {},
                keyboardVisible = true,
                onClickBalance = {},
                onContinueGeneral = {},
                onContinueKeyboard = {},
                tags = persistentListOf("Team", "Dinner", "Home"),
                onClickAddTag = {},
                onClickTag = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmallScreen() {
    AppThemeSurface {
        BottomSheetPreview {
            EditInvoiceContent(
                amountInputViewModel = previewAmountInputViewModel(),
                noteText = "Note text",
                onBack = {},
                onTextChanged = {},
                keyboardVisible = true,
                onClickBalance = {},
                onContinueGeneral = {},
                onContinueKeyboard = {},
                tags = persistentListOf("Team", "Dinner", "Home"),
                onClickAddTag = {},
                onClickTag = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
