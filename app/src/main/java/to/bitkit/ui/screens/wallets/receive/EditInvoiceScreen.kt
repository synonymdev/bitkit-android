package to.bitkit.ui.screens.wallets.receive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.WalletState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.keyboardAsState
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("ViewModelForwarding")
@Composable
fun EditInvoiceScreen(
    amountInputViewModel: AmountInputViewModel,
    walletUiState: WalletState,
    updateInvoice: (ULong?) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onDescriptionUpdate: (String) -> Unit,
    onBack: () -> Unit,
    navigateReceiveConfirm: (CjitEntryDetails) -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
    editInvoiceVM: EditInvoiceVM = hiltViewModel(),
) {
    val blocktankVM = blocktankViewModel ?: return
    var keyboardVisible by remember { mutableStateOf(false) }
    var isSoftKeyboardVisible by keyboardAsState()
    val amountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        editInvoiceVM.editInvoiceEffect.collect { effect ->
            val receiveSats = amountInputUiState.sats.toULong()
            when (effect) {
                is EditInvoiceVM.EditInvoiceScreenEffects.NavigateAddLiquidity -> {
                    updateInvoice(receiveSats)

                    if (receiveSats == 0UL) {
                        onBack()
                        return@collect
                    }

                    runCatching { blocktankVM.createCjit(receiveSats) }.onSuccess { entry ->
                        navigateReceiveConfirm(
                            CjitEntryDetails(
                                networkFeeSat = entry.networkFeeSat.toLong(),
                                serviceFeeSat = entry.serviceFeeSat.toLong(),
                                channelSizeSat = entry.channelSizeSat.toLong(),
                                feeSat = entry.feeSat.toLong(),
                                receiveAmountSats = receiveSats.toLong(),
                                invoice = entry.invoice.request,
                            )
                        )
                    }.onFailure { e ->
                        Logger.error("error creating cjit invoice", e, context = "EditInvoiceScreen")
                        onBack()
                    }
                }

                EditInvoiceVM.EditInvoiceScreenEffects.UpdateInvoice -> {
                    updateInvoice(receiveSats)
                    onBack()
                }
            }
        }
    }

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
        onContinueGeneral = { editInvoiceVM.onClickContinue() },
        onClickAddTag = onClickAddTag,
        onClickTag = onClickTag,
        isSoftKeyboardVisible = isSoftKeyboardVisible
    )
}

@Suppress("ViewModelForwarding")
@Composable
fun EditInvoiceContent(
    amountInputViewModel: AmountInputViewModel,
    noteText: String,
    isSoftKeyboardVisible: Boolean,
    keyboardVisible: Boolean,
    tags: List<String>,
    onBack: () -> Unit,
    onContinueKeyboard: () -> Unit,
    onClickBalance: () -> Unit,
    onContinueGeneral: () -> Unit,
    onClickAddTag: () -> Unit,
    onTextChanged: (String) -> Unit,
    onClickTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        val maxHeight = this.maxHeight

        AnimatedVisibility(
            visible = !keyboardVisible && !isSoftKeyboardVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomEnd)
        ) {
            Image(
                painter = painterResource(R.drawable.coin_stack),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag("edit_invoice_screen")
        ) {
            SheetTopBar(
                titleText = stringResource(R.string.wallet__receive_specify),
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("ReceiveAmount")
            ) {
                VerticalSpacer(16.dp)

                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    onClick = onClickBalance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPadTextField")
                )

                // Animated visibility for keyboard section
                AnimatedVisibility(
                    visible = keyboardVisible,
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween()
                    ) + fadeIn(),
                    exit = slideOutVertically(
                        targetOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween()
                    ) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.testTag("ReceiveNumberPad")
                    ) {
                        FillHeight(min = 12.dp)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UnitButton(
                                onClick = { amountInputViewModel.switchUnit(currencies) },
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("ReceiveNumberPadUnit")
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(top = 24.dp))

                        NumberPad(
                            viewModel = amountInputViewModel,
                            currencies = currencies,
                            availableHeight = maxHeight,
                            modifier = Modifier
                                .testTag("ReceiveNumberField")
                        )

                        PrimaryButton(
                            text = stringResource(R.string.common__continue),
                            onClick = onContinueKeyboard,
                            modifier = Modifier.testTag("ReceiveNumberPadSubmit")
                        )

                        VerticalSpacer(16.dp)
                    }
                }

                // Animated visibility for note section
                AnimatedVisibility(
                    visible = !keyboardVisible,
                    enter = fadeIn(animationSpec = tween()),
                    exit = fadeOut(animationSpec = tween())
                ) {
                    Column {
                        VerticalSpacer(44.dp)
                        Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)
                        VerticalSpacer(16.dp)

                        TextField(
                            placeholder = {
                                BodySSB(
                                    text = stringResource(R.string.wallet__receive_note_placeholder),
                                    color = Colors.White64
                                )
                            },
                            value = noteText,
                            onValueChange = onTextChanged,
                            minLines = 4,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                imeAction = ImeAction.Done
                            ),
                            colors = AppTextFieldDefaults.semiTransparent,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ReceiveNote")
                        )

                        VerticalSpacer(16.dp)
                        Caption13Up(text = stringResource(R.string.wallet__tags), color = Colors.White64)
                        VerticalSpacer(8.dp)

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            tags.map { tagText ->
                                TagButton(
                                    text = tagText,
                                    displayIconClose = true,
                                    onClick = { onClickTag(tagText) },
                                )
                            }
                        }
                        PrimaryButton(
                            text = stringResource(R.string.wallet__tags_add),
                            size = ButtonSize.Small,
                            onClick = { onClickAddTag() },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_tag),
                                    contentDescription = null,
                                    tint = Colors.Brand
                                )
                            },
                            fullWidth = false,
                            modifier = Modifier.testTag("TagsAdd")
                        )

                        FillHeight()

                        PrimaryButton(
                            text = stringResource(R.string.wallet__receive_show_qr),
                            onClick = onContinueGeneral,
                            modifier = Modifier.testTag("ShowQrReceive")
                        )

                        VerticalSpacer(16.dp)
                    }
                }
            }
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
                tags = listOf(),
                onClickAddTag = {},
                onClickTag = {},
                isSoftKeyboardVisible = false,
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
                tags = listOf("Team", "Dinner", "Home", "Work"),
                onClickAddTag = {},
                onClickTag = {},
                isSoftKeyboardVisible = false,
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
                tags = listOf("Team", "Dinner", "Home"),
                onClickAddTag = {},
                onClickTag = {},
                isSoftKeyboardVisible = false,
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
                tags = listOf("Team", "Dinner", "Home"),
                onClickAddTag = {},
                onClickTag = {},
                isSoftKeyboardVisible = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
