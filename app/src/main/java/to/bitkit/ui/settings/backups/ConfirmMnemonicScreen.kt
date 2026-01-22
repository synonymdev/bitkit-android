package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.effects.BlockScreenshots
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Suppress("CyclomaticComplexMethod")
@Composable
fun ConfirmMnemonicScreen(
    uiState: BackupContract.UiState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    BlockScreenshots()

    val originalSeed = remember(uiState.bip39Mnemonic) {
        uiState.bip39Mnemonic.split(" ").filter { it.isNotBlank() }
    }
    val shuffledWords = remember(originalSeed) {
        originalSeed.shuffled()
    }

    var selectedWords by rememberSaveable {
        mutableStateOf(arrayOfNulls<String>(originalSeed.size))
    }
    var pressedStates by rememberSaveable {
        mutableStateOf(BooleanArray(shuffledWords.size) { false })
    }

    // Calculate if all words are correct
    val isComplete = selectedWords.all { it != null } &&
        selectedWords.zip(originalSeed).all { (selected, original) -> selected == original }

    ConfirmMnemonicContent(
        originalSeed = originalSeed,
        shuffledWords = shuffledWords,
        selectedWords = selectedWords,
        pressedStates = pressedStates,
        isComplete = isComplete,
        onWordPress = { word, shuffledIndex ->
            // Find index of the last filled word
            val lastIndex = selectedWords.indexOfFirst { it == null } - 1
            val nextIndex = if (lastIndex == -1) 0 else lastIndex + 1

            // If the word is correct and pressed, do nothing
            if (pressedStates[shuffledIndex] && nextIndex > 0 && originalSeed[lastIndex] == selectedWords[lastIndex]) {
                return@ConfirmMnemonicContent
            }

            // If previous word is incorrect, allow unchecking
            if (lastIndex >= 0 && selectedWords[lastIndex] != originalSeed[lastIndex]) {
                // Uncheck if we tap on it
                if (pressedStates[shuffledIndex] && word == selectedWords[lastIndex]) {
                    pressedStates = pressedStates.copyOf().apply { this[shuffledIndex] = false }
                    selectedWords = selectedWords.copyOf().apply { this[lastIndex] = null }
                }
                return@ConfirmMnemonicContent
            }

            // Mark word as pressed and add it to the seed
            if (nextIndex < originalSeed.size) {
                pressedStates = pressedStates.copyOf().apply { this[shuffledIndex] = true }
                selectedWords = selectedWords.copyOf().apply { this[nextIndex] = word }
            }
        },
        onContinue = onContinue,
        onBack = onBack,
    )
}

@Composable
private fun ConfirmMnemonicContent(
    originalSeed: List<String>,
    shuffledWords: List<String>,
    selectedWords: Array<String?>,
    pressedStates: BooleanArray,
    isComplete: Boolean,
    onWordPress: (String, Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Autoscroll to bottom when all words are correctly selected
    LaunchedEffect(isComplete) {
        if (isComplete) {
            delay(300) // Wait for any UI updates to complete
            scope.launch {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("backup_confirm_mnemonic_screen")
    ) {
        SheetTopBar(stringResource(R.string.security__mnemonic_confirm), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(scrollState)
        ) {
            BodyM(
                text = stringResource(R.string.security__mnemonic_confirm_tap).replace("12", "${originalSeed.size}"),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Shuffled word buttons
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_shuffled_words_grid")
            ) {
                shuffledWords.forEachIndexed { index, word ->
                    val isSelected = pressedStates.getOrElse(index, defaultValue = { false })
                    PrimaryButton(
                        text = word,
                        color = if (isSelected) Colors.White32 else Colors.White16,
                        enableGradient = !isSelected,
                        fullWidth = false,
                        size = ButtonSize.Small,
                        onClick = { onWordPress(word, index) },
                        modifier = Modifier.testTag("Word-$word")
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Selected words display (2 columns)
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedWords.take(selectedWords.size / 2).forEachIndexed { index, word ->
                        SelectedWordItem(
                            number = index + 1,
                            word = word ?: "",
                            isCorrect = word == originalSeed.getOrNull(index),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedWords.drop(selectedWords.size / 2).forEachIndexed { index, word ->
                        val actualIndex = selectedWords.size / 2 + index
                        SelectedWordItem(
                            number = actualIndex + 1,
                            word = word ?: "",
                            isCorrect = word == originalSeed.getOrNull(actualIndex),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = isComplete,
                modifier = Modifier.testTag("ContinueConfirmMnemonic")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SelectedWordItem(
    number: Int,
    word: String,
    isCorrect: Boolean,
) {
    Row {
        BodyMSB(text = "$number.", color = Colors.White64)
        Spacer(modifier = Modifier.width(4.dp))
        BodyMSB(
            text = word.ifEmpty { "" },
            color = if (word.isEmpty()) Colors.White64 else if (isCorrect) Colors.Green else Colors.Red
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    val testWords = List(12) { "word${it + 1}" }
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords.shuffled(),
            selectedWords = arrayOfNulls(testWords.size),
            pressedStates = BooleanArray(testWords.size) { false },
            isComplete = false,
            onWordPress = { _, _ -> },
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    val testWords = List(12) { "word${it + 1}" }
    val half = testWords.size / 2
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords.shuffled(),
            selectedWords = testWords.take(half).toTypedArray<String?>() + arrayOfNulls<String>(half),
            pressedStates = BooleanArray(testWords.size) { it < half },
            isComplete = false,
            onWordPress = { _, _ -> },
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview24Words() {
    val testWords = List(24) { "word${it + 1}" }
    val half = testWords.size / 2
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords.shuffled(),
            selectedWords = testWords.take(half).toTypedArray<String?>() + arrayOfNulls<String>(half),
            pressedStates = BooleanArray(testWords.size) { it < half },
            isComplete = false,
            onWordPress = { _, _ -> },
            onContinue = {},
            onBack = {},
        )
    }
}
