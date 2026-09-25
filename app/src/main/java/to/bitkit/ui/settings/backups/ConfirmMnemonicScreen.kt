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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.effects.BlockScreenshots
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ConfirmMnemonicScreen(
    uiState: BackupContract.UiState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    BlockScreenshots()

    val originalSeed = remember(uiState.bip39Mnemonic) {
        uiState.bip39Mnemonic.split(" ").filter { it.isNotBlank() }.toImmutableList()
    }
    val shuffledOrder = rememberSaveable(originalSeed) { originalSeed.indices.shuffled() }
    val shuffledWords = remember(originalSeed, shuffledOrder) {
        shuffledOrder.map { originalSeed[it] }.toImmutableList()
    }

    var selectedIndices by rememberSaveable(originalSeed) {
        mutableStateOf(listOf<Int>())
    }

    val isComplete = isMnemonicSelectionComplete(selectedIndices, shuffledWords, originalSeed)

    ConfirmMnemonicContent(
        originalSeed = originalSeed,
        shuffledWords = shuffledWords,
        selectedIndices = selectedIndices.toImmutableList(),
        isComplete = isComplete,
        onWordPress = { shuffledIndex ->
            selectedIndices = reduceMnemonicSelection(selectedIndices, shuffledIndex, shuffledWords, originalSeed)
        },
        onSelectedWordPress = {
            val lastIndex = selectedIndices.lastOrNull() ?: return@ConfirmMnemonicContent
            selectedIndices = reduceMnemonicSelection(selectedIndices, lastIndex, shuffledWords, originalSeed)
        },
        onContinue = onContinue,
        onBack = onBack,
    )
}

/**
 * Applies a tap on the shuffled word chip at [tappedShuffledIndex] to the [stack] of selected chip indices.
 * An incorrect last word blocks further selection and is removed only by tapping its own chip.
 */
internal fun reduceMnemonicSelection(
    stack: List<Int>,
    tappedShuffledIndex: Int,
    shuffled: List<String>,
    original: List<String>,
): List<Int> {
    if (tappedShuffledIndex !in shuffled.indices) return stack
    if (!isSelectionPositionCorrect(stack, stack.lastIndex, shuffled, original)) {
        return if (stack.last() == tappedShuffledIndex) stack.dropLast(1) else stack
    }
    if (tappedShuffledIndex in stack) return stack
    if (stack.size >= original.size) return stack
    return stack + tappedShuffledIndex
}

internal fun isMnemonicSelectionComplete(
    stack: List<Int>,
    shuffled: List<String>,
    original: List<String>,
): Boolean = original.isNotEmpty() &&
    stack.size == original.size &&
    stack.indices.all { isSelectionPositionCorrect(stack, it, shuffled, original) }

private fun isSelectionPositionCorrect(
    stack: List<Int>,
    position: Int,
    shuffled: List<String>,
    original: List<String>,
): Boolean {
    if (position < 0) return true
    val word = shuffled.getOrNull(stack[position]) ?: return false
    return word == original.getOrNull(position)
}

@Composable
private fun ConfirmMnemonicContent(
    originalSeed: ImmutableList<String>,
    shuffledWords: ImmutableList<String>,
    selectedIndices: ImmutableList<Int>,
    isComplete: Boolean,
    onWordPress: (Int) -> Unit,
    onSelectedWordPress: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val selectedWords = remember(originalSeed, shuffledWords, selectedIndices) {
        List(originalSeed.size) { position ->
            selectedIndices.getOrNull(position)?.let { shuffledWords.getOrNull(it) }
        }.toImmutableList()
    }
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
                    val isSelected = index in selectedIndices
                    PrimaryButton(
                        text = word,
                        color = if (isSelected) Colors.White32 else Colors.White16,
                        enableGradient = !isSelected,
                        fullWidth = false,
                        size = ButtonSize.Small,
                        onClick = { onWordPress(index) },
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
                            onClick = onSelectedWordPress,
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
                            onClick = onSelectedWordPress,
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
    onClick: () -> Unit,
) {
    val isIncorrect = word.isNotEmpty() && !isCorrect
    Row(
        modifier = Modifier
            .clickableAlpha(enabled = isIncorrect, onClick = onClick)
            .testTag("SelectedWord-$number")
    ) {
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
    val testWords = List(12) { "word${it + 1}" }.toImmutableList()
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords.shuffled().toImmutableList(),
            selectedIndices = persistentListOf(),
            isComplete = false,
            onWordPress = {},
            onSelectedWordPress = {},
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    val testWords = List(12) { "word${it + 1}" }.toImmutableList()
    val half = testWords.size / 2
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords,
            selectedIndices = List(half) { it }.toImmutableList(),
            isComplete = false,
            onWordPress = {},
            onSelectedWordPress = {},
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview24Words() {
    val testWords = List(24) { "word${it + 1}" }.toImmutableList()
    val half = testWords.size / 2
    AppThemeSurface {
        ConfirmMnemonicContent(
            originalSeed = testWords,
            shuffledWords = testWords,
            selectedIndices = List(half) { it }.toImmutableList(),
            isComplete = false,
            onWordPress = {},
            onSelectedWordPress = {},
            onContinue = {},
            onBack = {},
        )
    }
}
