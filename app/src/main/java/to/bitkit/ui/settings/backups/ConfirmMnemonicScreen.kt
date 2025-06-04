package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.bip39Words

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmMnemonicScreen(
    seed: List<String>,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    // State to track user selection
    var selectedWords by remember { mutableStateOf(arrayOfNulls<String>(seed.size)) }
    var pressedStates by remember { mutableStateOf(BooleanArray(seed.size) { false }) }

    // Shuffle the words for selection
    val shuffledWords = remember { seed.shuffled() }

    DisposableEffect(Unit) {
        onDispose {
            // Clear selected words from memory
            selectedWords = arrayOfNulls(seed.size)
        }
    }

    ConfirmMnemonicContent(
        originalSeed = seed,
        shuffledWords = shuffledWords,
        selectedWords = selectedWords,
        pressedStates = pressedStates,
        onWordPress = { word, shuffledIndex ->
            // Find index of the last filled word
            val firstNullIndex = selectedWords.indexOfFirst { it == null }
            val lastFilledIndex = if (firstNullIndex == -1) selectedWords.size - 1 else firstNullIndex - 1
            val nextEmptyIndex = if (firstNullIndex == -1) -1 else firstNullIndex

            // If this word is already pressed/selected
            if (pressedStates[shuffledIndex]) {
                // Allow deselecting only if it's the last word that was selected
                // or if the word at the last position is incorrect
                if (lastFilledIndex >= 0) {
                    val wordAtLastPosition = selectedWords[lastFilledIndex]
                    val isLastWordIncorrect = wordAtLastPosition != seed[lastFilledIndex]
                    val isThisTheLastWord = wordAtLastPosition == word

                    if (isThisTheLastWord && (isLastWordIncorrect || firstNullIndex == -1)) {
                        // Deselect this word
                        pressedStates = pressedStates.copyOf().apply { this[shuffledIndex] = false }
                        selectedWords = selectedWords.copyOf().apply { this[lastFilledIndex] = null }
                    }
                }
                return@ConfirmMnemonicContent
            }

            // If we have space and word is not already pressed, add it
            if (nextEmptyIndex >= 0 && nextEmptyIndex < seed.size) {
                pressedStates = pressedStates.copyOf().apply { this[shuffledIndex] = true }
                selectedWords = selectedWords.copyOf().apply { this[nextEmptyIndex] = word }
            }
        },
        onContinue = onContinue,
        onBack = onBack,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmMnemonicContent(
    originalSeed: List<String>,
    shuffledWords: List<String>,
    selectedWords: Array<String?>,
    pressedStates: BooleanArray,
    onWordPress: (String, Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    // Check if all words are correct
    val isComplete = selectedWords.all { it != null } &&
        selectedWords.zip(originalSeed).all { (selected, original) -> selected == original }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__mnemonic_confirm),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            BodyM(
                text = stringResource(R.string.security__mnemonic_confirm_tap),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Shuffled word buttons
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                shuffledWords.forEachIndexed { index, word ->
                    PrimaryButton(
                        text = word,
                        color = if (pressedStates[index]) Colors.White32 else Colors.White16,
                        fullWidth = false,
                        size = ButtonSize.Small,
                        onClick = { onWordPress(word, index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

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
                            correct = word == originalSeed[index]
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
                            correct = word == originalSeed[actualIndex]
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = isComplete,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SelectedWordItem(
    number: Int,
    word: String,
    correct: Boolean,
) {
    Row {
        BodyMSB(text = "$number.", color = Colors.White64)
        Spacer(modifier = Modifier.width(4.dp))
        BodyMSB(
            text = if (word.isEmpty()) "" else word,
            color = if (word.isEmpty()) Colors.White64 else if (correct) Colors.Green else Colors.Red
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        val testSeed = listOf("abandon", "ability", "able", "about", "above", "absent")
        ConfirmMnemonicContent(
            originalSeed = testSeed,
            shuffledWords = testSeed.shuffled(),
            selectedWords = arrayOfNulls(testSeed.size),
            pressedStates = BooleanArray(testSeed.size) { false },
            onWordPress = { _, _ -> },
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        val testSeed = List(24) { bip39Words.random() }
        ConfirmMnemonicContent(
            originalSeed = testSeed,
            shuffledWords = testSeed.shuffled(),
            selectedWords = testSeed.take(12).toTypedArray<String?>() + arrayOfNulls(12),
            pressedStates = BooleanArray(testSeed.size) { it < 12 },
            onWordPress = { _, _ -> },
            onContinue = {},
            onBack = {},
        )
    }
}
