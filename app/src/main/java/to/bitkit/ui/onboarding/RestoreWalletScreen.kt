package to.bitkit.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.RestoreWalletUiState
import to.bitkit.viewmodels.RestoreWalletViewModel

@Composable
fun RestoreWalletScreen(
    onBackClick: () -> Unit,
    onRestoreClick: (mnemonic: String, passphrase: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RestoreWalletViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        checksumErrorVisible = uiState.checksumErrorVisible,
        areButtonsEnabled = uiState.areButtonsEnabled,
        onChangeWord = viewModel::onChangeWord,
        onChangeWordFocus = viewModel::onChangeWordFocus,
        onChangePassphrase = viewModel::onChangePassphrase,
        onBackspaceInEmpty = viewModel::onBackspaceInEmpty,
        onSelectSuggestion = viewModel::onSelectSuggestion,
        onKeyboardDismiss = viewModel::onKeyboardDismiss,
        onScrollComplete = viewModel::onScrollComplete,
        onAdvancedClick = viewModel::onAdvancedClick,
        onBack = onBackClick,
        onRestore = onRestoreClick,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: RestoreWalletUiState,
    checksumErrorVisible: Boolean,
    areButtonsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onChangeWord: (Int, String) -> Unit = { _, _ -> },
    onChangeWordFocus: (Int, Boolean) -> Unit = { _, _ -> },
    onChangePassphrase: (String) -> Unit = {},
    onBackspaceInEmpty: (Int) -> Unit = {},
    onSelectSuggestion: (String) -> Unit = {},
    onKeyboardDismiss: () -> Unit = {},
    onScrollComplete: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onRestore: (mnemonic: String, passphrase: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val inputFieldPositions = remember { mutableMapOf<Int, Int>() }
    val focusRequesters = remember(uiState.wordCount) { List(uiState.wordCount) { FocusRequester() } }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val currentOnKeyboardDismiss by rememberUpdatedState(onKeyboardDismiss)
    val currentOnScrollComplete by rememberUpdatedState(onScrollComplete)

    LaunchedEffect(uiState.shouldDismissKeyboard) {
        if (uiState.shouldDismissKeyboard) {
            focusManager.clearFocus()
            keyboardController?.hide()
            currentOnKeyboardDismiss()
        }
    }

    LaunchedEffect(uiState.scrollToFieldIndex) {
        uiState.scrollToFieldIndex?.let { index ->
            inputFieldPositions[index]?.let { position ->
                scrollState.animateScrollTo(position)
            }
            currentOnScrollComplete()
        }
    }

    LaunchedEffect(uiState.focusedIndex) {
        uiState.focusedIndex?.let { index ->
            focusRequesters[index].requestFocus()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                titleText = null,
                onBackClick = onBack,
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .verticalScroll(scrollState)
            ) {
                Display(stringResource(R.string.onboarding__restore_header).withAccent(accentColor = Colors.Blue))
                VerticalSpacer(8.dp)
                BodyM(
                    text = stringResource(R.string.onboarding__restore_phrase),
                    color = Colors.White80,
                )
                VerticalSpacer(32.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // First column (1-6 or 1-12)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        for (index in 0 until uiState.wordsPerColumn) {
                            MnemonicInputField(
                                label = "${index + 1}.",
                                value = uiState.words[index],
                                isError = index in uiState.invalidWordIndices && uiState.focusedIndex != index,
                                onValueChange = { onChangeWord(index, it) },
                                onFocusChange = { focused -> onChangeWordFocus(index, focused) },
                                onPositionChange = { position -> inputFieldPositions[index] = position },
                                onBackspaceInEmpty = { onBackspaceInEmpty(index) },
                                focusRequester = focusRequesters[index],
                                index = index,
                            )
                        }
                    }
                    // Second column (7-12 or 13-24)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        for (index in uiState.wordsPerColumn until (uiState.wordsPerColumn * 2)) {
                            MnemonicInputField(
                                label = "${index + 1}.",
                                value = uiState.words[index],
                                isError = index in uiState.invalidWordIndices && uiState.focusedIndex != index,
                                onValueChange = { onChangeWord(index, it) },
                                onFocusChange = { focused -> onChangeWordFocus(index, focused) },
                                onPositionChange = { position -> inputFieldPositions[index] = position },
                                onBackspaceInEmpty = { onBackspaceInEmpty(index) },
                                focusRequester = focusRequesters[index],
                                index = index,
                            )
                        }
                    }
                }

                // Passphrase
                if (uiState.showingPassphrase) {
                    TextInput(
                        value = uiState.bip39Passphrase,
                        onValueChange = onChangePassphrase,
                        placeholder = stringResource(R.string.onboarding__restore_passphrase_placeholder),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("PassphraseInput")
                    )
                    VerticalSpacer(16.dp)
                    BodyS(
                        text = stringResource(R.string.onboarding__restore_passphrase_meaning),
                        color = Colors.White64,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Spacer(
                    modifier = Modifier
                        .height(16.dp)
                        .weight(1f)
                )

                AnimatedVisibility(visible = uiState.invalidWordIndices.any { it != uiState.focusedIndex }) {
                    BodyS(
                        text = stringResource(
                            R.string.onboarding__restore_red_explain
                        ).withAccent(accentColor = Colors.Red),
                        color = Colors.White64,
                        modifier = Modifier.padding(top = 21.dp)
                    )
                }

                AnimatedVisibility(visible = checksumErrorVisible) {
                    BodyS(
                        text = stringResource(R.string.onboarding__restore_inv_checksum),
                        color = Colors.Red,
                        modifier = Modifier.padding(top = 21.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth(),
                ) {
                    AnimatedVisibility(visible = !uiState.showingPassphrase, modifier = Modifier.weight(1f)) {
                        SecondaryButton(
                            text = stringResource(R.string.onboarding__advanced),
                            onClick = { onAdvancedClick() },
                            enabled = areButtonsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("AdvancedButton")
                        )
                    }
                    PrimaryButton(
                        text = stringResource(R.string.onboarding__restore),
                        onClick = {
                            onRestore(uiState.bip39Mnemonic, uiState.bip39Passphrase.takeIf { it.isNotEmpty() })
                        },
                        enabled = areButtonsEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("RestoreButton")
                    )
                }
            }

            SuggestionsRow(
                suggestions = uiState.suggestions,
                onSelect = { onSelectSuggestion(it) }
            )
        }
    }
}

@Composable
private fun BoxScope.SuggestionsRow(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Colors.Black)
                .padding(horizontal = 32.dp, vertical = 8.dp)
        ) {
            BodyS(
                text = stringResource(R.string.onboarding__restore_suggestions),
                color = Colors.White64,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                suggestions.forEach { suggestion ->
                    PrimaryButton(
                        text = suggestion,
                        onClick = { onSelect(suggestion) },
                        size = ButtonSize.Small,
                        fullWidth = false
                    )
                }
            }
        }
    }
}

@Composable
fun MnemonicInputField(
    label: String,
    isError: Boolean = false,
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onPositionChange: (Int) -> Unit,
    onBackspaceInEmpty: () -> Unit,
    focusRequester: FocusRequester,
    index: Int,
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }

    // Sync text from parent while preserving selection
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            val selection = textFieldValue.selection
            textFieldValue = TextFieldValue(value, selection)
        }
    }

    OutlinedTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
            onValueChange(it.text)
        },
        textStyle = AppTextStyles.BodySSB,
        prefix = {
            Text(
                text = label,
                color = if (isError) Colors.Red else Colors.White64,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 4.dp)
            )
        },
        isError = isError,
        shape = RoundedCornerShape(8.dp),
        colors = AppTextFieldDefaults.semiTransparent,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
            capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Backspace &&
                    keyEvent.type == KeyEventType.KeyDown &&
                    value.isEmpty()
                ) {
                    onBackspaceInEmpty()
                    true
                } else {
                    false
                }
            }
            .testTag("Word-$index")
            .onFocusChanged { focusState -> onFocusChange(focusState.isFocused) }
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInParent().y.toInt() * 2 // double the scroll to ensure enough space
                onPositionChange(position)
            }
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = RestoreWalletUiState(),
            checksumErrorVisible = false,
            areButtonsEnabled = false,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewAdvanced() {
    AppThemeSurface {
        Content(
            uiState = RestoreWalletUiState(
                showingPassphrase = true,
            ),
            checksumErrorVisible = false,
            areButtonsEnabled = false,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewValid() {
    AppThemeSurface {
        Content(
            uiState = RestoreWalletUiState(
                words = List(12) { if (it % 2 == 0) "abandon" else "ability" },
                is24Words = false,
            ),
            checksumErrorVisible = false,
            areButtonsEnabled = true,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewInvalid() {
    AppThemeSurface {
        Content(
            uiState = RestoreWalletUiState(
                words = List(12) { if (it % 2 == 0) "rock" else "roll" },
                is24Words = false,
            ),
            checksumErrorVisible = true,
            areButtonsEnabled = false,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview24Words() {
    AppThemeSurface {
        @Suppress("MagicNumber")
        Content(
            uiState = RestoreWalletUiState(
                is24Words = true,
                words = List(24) { "word${it + 1}" }
            ),
            checksumErrorVisible = false,
            areButtonsEnabled = false,
        )
    }
}
