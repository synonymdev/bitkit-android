package to.bitkit.ui.screens.wallets.receive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.WalletState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInputHandler
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.keyboardAsState
import to.bitkit.viewmodels.CurrencyUiState

@Composable
fun EditInvoiceScreen(
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
    editInvoiceVM: EditInvoiceVM = hiltViewModel(),
    walletUiState: WalletState,
    updateInvoice: (ULong?) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onInputUpdated: (String) -> Unit,
    onDescriptionUpdate: (String) -> Unit,
    onBack: () -> Unit,
    navigateReceiveConfirm: (CjitEntryDetails) -> Unit,
) {
    val currencyVM = currencyViewModel ?: return
    var satsString by rememberSaveable { mutableStateOf("") }
    var keyboardVisible by remember { mutableStateOf(false) }
    var isSoftKeyboardVisible by keyboardAsState()

    LaunchedEffect(Unit) {
        editInvoiceVM.editInvoiceEffect.collect { effect ->
            when(effect) {
                is EditInvoiceVM.EditInvoiceScreenEffects.NavigateAddLiquidity -> {
                    navigateReceiveConfirm(effect.cjit)
                }
                EditInvoiceVM.EditInvoiceScreenEffects.UpdateInvoice -> { updateInvoice(satsString.toULongOrNull()) }
            }
        }
    }

    AmountInputHandler(
        input = walletUiState.balanceInput,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onInputChanged = onInputUpdated,
        onAmountCalculated = { sats -> satsString = sats },
        currencyVM = currencyVM
    )

    EditInvoiceContent(
        input = walletUiState.balanceInput,
        noteText = walletUiState.bip21Description,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        tags = walletUiState.selectedTags,
        onBack = onBack,
        onTextChanged = onDescriptionUpdate,
        numericKeyboardVisible = keyboardVisible,
        onClickBalance = { keyboardVisible = true },
        onInputChanged = onInputUpdated,
        onContinueKeyboard = { keyboardVisible = false },
        onContinueGeneral = { editInvoiceVM.onClickContinue() },
        onClickAddTag = onClickAddTag,
        onClickTag = onClickTag,
        isSoftKeyboardVisible = isSoftKeyboardVisible
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditInvoiceContent(
    input: String,
    noteText: String,
    isSoftKeyboardVisible: Boolean,
    numericKeyboardVisible: Boolean,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    tags: List<String>,
    onBack: () -> Unit,
    onContinueKeyboard: () -> Unit,
    onClickBalance: () -> Unit,
    onContinueGeneral: () -> Unit,
    onClickAddTag: () -> Unit,
    onTextChanged: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onInputChanged: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
    ) {

        AnimatedVisibility(
            visible = !numericKeyboardVisible && !isSoftKeyboardVisible,
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
            SheetTopBar(stringResource(R.string.wallet__receive_specify)) {
                onBack()
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("edit_invoice_content")
            ) {
                Spacer(Modifier.height(32.dp))

                NumberPadTextField(
                    input = input,
                    displayUnit = displayUnit,
                    primaryDisplay = primaryDisplay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableAlpha(onClick = onClickBalance)
                        .testTag("amount_input_field")
                )

                // Animated visibility for keyboard section
                AnimatedVisibility(
                    visible = numericKeyboardVisible,
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = 300)
                    ) + fadeIn(),
                    exit = slideOutVertically(
                        targetOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = 300)
                    ) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.testTag("keyboard_section")
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UnitButton(modifier = Modifier.height(28.dp))
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                        Keyboard(
                            onClick = { number ->
                                onInputChanged(if (input == "0") number else input + number)
                            },
                            onClickBackspace = {
                                onInputChanged(if (input.length > 1) input.dropLast(1) else "0")
                            },
                            isDecimal = primaryDisplay == PrimaryDisplay.FIAT,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("amount_keyboard"),
                        )

                        Spacer(modifier = Modifier.height(41.dp))

                        PrimaryButton(
                            text = stringResource(R.string.common__continue),
                            onClick = onContinueKeyboard,
                            modifier = Modifier.testTag("keyboard_continue_button")
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // Animated visibility for note section
                AnimatedVisibility(
                    visible = !numericKeyboardVisible,
                    enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 300))
                ) {
                    Column(
                        modifier = Modifier.testTag("note_section")
                    ) {
                        Spacer(modifier = Modifier.height(44.dp))

                        Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)

                        Spacer(modifier = Modifier.height(16.dp))

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
                                .testTag("note_input_field")

                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Caption13Up(text = stringResource(R.string.wallet__tags), color = Colors.White64)
                        Spacer(modifier = Modifier.height(8.dp))
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
                                    isSelected = false,
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
                            fullWidth = false
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        PrimaryButton(
                            text = stringResource(R.string.wallet__receive_show_qr),
                            onClick = onContinueGeneral,
                            modifier = Modifier.testTag("general_continue_button")
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onBack = {},
            onTextChanged = {},
            numericKeyboardVisible = false,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {},
            tags = listOf(),
            onClickAddTag = {},
            onClickTag = {},
            isSoftKeyboardVisible = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "Note text",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onBack = {},
            onTextChanged = {},
            numericKeyboardVisible = false,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {},
            tags = listOf("Team", "Dinner", "Home", "Work"),
            onClickAddTag = {},
            onClickTag = {},
            isSoftKeyboardVisible = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview3() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "Note text",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onBack = {},
            onTextChanged = {},
            numericKeyboardVisible = true,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {},
            tags = listOf("Team", "Dinner","Home"),
            onClickAddTag = {},
            onClickTag = {},
            isSoftKeyboardVisible = false,
        )
    }
}
