package to.bitkit.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.bip39Words
import to.bitkit.utils.isBip39
import to.bitkit.utils.validBip39Checksum

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWalletView(
    onBackClick: () -> Unit,
    onRestoreClick: (mnemonic: String, passphrase: String?) -> Unit,
) {
    val words = remember { mutableStateListOf(*Array(24) { "" }) }
    val invalidWordIndices = remember { mutableStateListOf<Int>() }
    val suggestions = remember { mutableStateListOf<String>() }
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    var bip39Passphrase by remember { mutableStateOf("") }
    var showingPassphrase by remember { mutableStateOf(false) }
    var firstFieldText by remember { mutableStateOf("") }
    var is24Words by remember { mutableStateOf(false) }
    val checksumErrorVisible by remember {
        derivedStateOf {
            val wordCount = if (is24Words) 24 else 12
            words.subList(0, wordCount).none { it.isBlank() } && invalidWordIndices.isEmpty() && !words.subList(
                0,
                wordCount
            ).validBip39Checksum()
        }
    }

    val wordsPerColumn = if (is24Words) 12 else 6

    val bip39Mnemonic by remember {
        derivedStateOf {
            val wordCount = if (is24Words) 24 else 12
            words.subList(0, wordCount)
                .joinToString(separator = " ")
                .trim()
        }
    }

    fun updateSuggestions(input: String, index: Int?) {
        if (index == null) {
            suggestions.clear()
            return
        }

        suggestions.clear()
        if (input.isNotEmpty()) {
            val filtered = bip39Words.filter { it.startsWith(input.lowercase()) }.take(3)
            suggestions.addAll(filtered)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            Display(stringResource(R.string.onboarding__restore_header).withAccent(accentColor = Colors.Blue))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = stringResource(R.string.onboarding__restore_phrase),
                color = Colors.White80,
            )
            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // First column (1-6 or 1-12)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    for (index in 0 until wordsPerColumn) {
                        MnemonicInputField(
                            label = "${index + 1}.",
                            value = if (index == 0) firstFieldText else words[index],
                            isError = index in invalidWordIndices,
                            onValueChanged = { newValue ->
                                if (index == 0) {
                                    if (newValue.contains(" ")) {
                                        handlePastedWords(
                                            newValue,
                                            words,
                                            onWordCountChanged = { is24Words = it },
                                            onFirstWordChanged = { firstFieldText = it },
                                            onInvalidWords = { invalidIndices ->
                                                invalidWordIndices.clear()
                                                invalidWordIndices.addAll(invalidIndices)
                                            }
                                        )
                                    } else {
                                        updateWordValidity(
                                            newValue,
                                            index,
                                            words,
                                            invalidWordIndices,
                                            onWordUpdate = { firstFieldText = it }
                                        )
                                        updateSuggestions(newValue, focusedIndex)
                                    }
                                } else {
                                    updateWordValidity(
                                        newValue,
                                        index,
                                        words,
                                        invalidWordIndices,
                                    )
                                    updateSuggestions(newValue, focusedIndex)
                                }
                            },
                            onFocusChanged = { focused ->
                                if (focused) {
                                    focusedIndex = index
                                    updateSuggestions(if (index == 0) firstFieldText else words[index], index)
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                    suggestions.clear()
                                }
                            }
                        )
                    }
                }
                // Second column (7-12 or 13-24)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    for (index in wordsPerColumn until (wordsPerColumn * 2)) {
                        MnemonicInputField(
                            label = "${index + 1}.",
                            value = words[index],
                            isError = index in invalidWordIndices,
                            onValueChanged = { newValue ->
                                words[index] = newValue

                                updateWordValidity(
                                    newValue,
                                    index,
                                    words,
                                    invalidWordIndices,
                                )
                                updateSuggestions(newValue, focusedIndex)
                            },
                            onFocusChanged = { focused ->
                                if (focused) {
                                    focusedIndex = index
                                    updateSuggestions(words[index], index)
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                    suggestions.clear()
                                }
                            }
                        )
                    }
                }
            }
            // Passphrase
            if (showingPassphrase) {
                OutlinedTextField(
                    value = bip39Passphrase,
                    onValueChange = { bip39Passphrase = it },
                    placeholder = { Text(text = stringResource(R.string.onboarding__restore_passphrase_placeholder)) },
                    shape = RoundedCornerShape(8.dp),
                    colors = AppTextFieldDefaults.semiTransparent,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                BodyS(
                    text = stringResource(R.string.onboarding__restore_passphrase_meaning),
                    color = Colors.White64,
                )
            }

            Spacer(
                modifier = Modifier
                    .height(16.dp)
                    .weight(1f)
            )

            AnimatedVisibility(visible = suggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 29.dp)
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
                                onClick = {
                                    focusedIndex?.let { index ->
                                        if (index == 0) {
                                            firstFieldText = suggestion
                                            updateWordValidity(
                                                suggestion,
                                                index,
                                                words,
                                                invalidWordIndices,
                                                onWordUpdate = { firstFieldText = it }
                                            )
                                        } else {
                                            updateWordValidity(
                                                suggestion,
                                                index,
                                                words,
                                                invalidWordIndices,
                                            )
                                        }
                                        suggestions.clear()
                                    }
                                },
                                size = ButtonSize.Small,
                                fullWidth = false
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = invalidWordIndices.isNotEmpty()) {
                BodyS(
                    text = stringResource(R.string.onboarding__restore_red_explain).withAccent(accentColor = Colors.Red),
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
                val areButtonsEnabled by remember {
                    derivedStateOf {
                        val wordCount = if (is24Words) 24 else 12
                        words.subList(0, wordCount)
                            .none { it.isBlank() } && invalidWordIndices.isEmpty() && !checksumErrorVisible
                    }
                }
                SecondaryButton(
                    text = stringResource(R.string.onboarding__advanced),
                    onClick = {
                        showingPassphrase = !showingPassphrase
                        bip39Passphrase = ""
                    },
                    enabled = areButtonsEnabled,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.onboarding__restore),
                    onClick = {
                        onRestoreClick(bip39Mnemonic, bip39Passphrase.takeIf { it.isNotEmpty() })
                    },
                    enabled = areButtonsEnabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun MnemonicInputField(
    label: String,
    isError: Boolean = false,
    value: String,
    onValueChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
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
        modifier = Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
    )
}

private fun handlePastedWords(
    pastedText: String,
    words: SnapshotStateList<String>,
    onWordCountChanged: (Boolean) -> Unit,
    onFirstWordChanged: (String) -> Unit,
    onInvalidWords: (List<Int>) -> Unit
) {
    val pastedWords = pastedText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    if (pastedWords.size == 12 || pastedWords.size == 24) {

        val invalidWordIndices = pastedWords.withIndex()
            .filter { !it.value.isBip39() }
            .map { it.index }

        if (invalidWordIndices.isNotEmpty()) {
            onInvalidWords(invalidWordIndices)
        }

        onWordCountChanged(pastedWords.size == 24)
        for (index in pastedWords.indices) {
            words[index] = pastedWords[index]
        }
        for (index in pastedWords.size until words.size) {
            words[index] = ""
        }
        onFirstWordChanged(pastedWords.first())
    }
}

private fun updateWordValidity(
    newValue: String,
    index: Int,
    words: SnapshotStateList<String>,
    invalidWordIndices: SnapshotStateList<Int>,
    onWordUpdate: ((String) -> Unit)? = null
) {
    words[index] = newValue
    onWordUpdate?.invoke(newValue)

    val isValid = newValue.isBip39()
    if (!isValid && newValue.isNotEmpty()) {
        if (!invalidWordIndices.contains(index)) {
            invalidWordIndices.add(index)
        }
    } else {
        invalidWordIndices.remove(index)
    }
}

@Preview(showSystemUi = true)
@Composable
fun RestoreWalletViewPreview() {
    AppThemeSurface {
        RestoreWalletView(
            onBackClick = {},
            onRestoreClick = { _, _ -> },
        )
    }
}
