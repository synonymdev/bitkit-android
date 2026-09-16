package to.bitkit.viewmodels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.services.core.Bip39Service
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreWalletViewModelTest : BaseUnitTest() {

    private val bip39Service = mock<Bip39Service>()

    private lateinit var viewModel: RestoreWalletViewModel

    @Before
    fun setup() = runBlocking {
        whenever(bip39Service.isValidWord(any())).thenReturn(true)
        whenever(bip39Service.getSuggestions(any(), any())).thenReturn(emptyList())
        whenever(bip39Service.isValidMnemonicSize(any())).thenReturn(true)
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.success(Unit))

        viewModel = RestoreWalletViewModel(bip39Service)
    }

    // region Initial State

    @Test
    fun `initial state should have 24 empty word slots`() {
        val state = viewModel.uiState.value

        assertEquals(24, state.words.size)
        assertTrue(state.words.all { it.isEmpty() })
    }

    @Test
    fun `initial state should have focused index 0`() {
        val state = viewModel.uiState.value

        assertEquals(0, state.focusedIndex)
    }

    @Test
    fun `initial state should be in 12-word mode`() {
        val state = viewModel.uiState.value

        assertFalse(state.is24Words)
        assertEquals(12, state.wordCount)
    }

    @Test
    fun `initial state should have no suggestions`() {
        val state = viewModel.uiState.value

        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `initial state should have passphrase section hidden`() {
        val state = viewModel.uiState.value

        assertFalse(state.showingPassphrase)
        assertEquals("", state.bip39Passphrase)
    }

    @Test
    fun `initial state should have checksumErrorVisible false`() {
        val state = viewModel.uiState.value

        assertFalse(state.checksumErrorVisible)
    }

    @Test
    fun `initial state should have areButtonsEnabled false`() {
        val state = viewModel.uiState.value

        assertFalse(state.areButtonsEnabled)
    }

    // endregion

    // region Word Input

    @Test
    fun `onChangeWord should update word at correct index`() {
        viewModel.onChangeWord(5, "abandon")

        val state = viewModel.uiState.value
        assertEquals("abandon", state.words[5])
    }

    @Test
    fun `onChangeWord should update scrollToFieldIndex`() {
        viewModel.onChangeWord(7, "ability")

        val state = viewModel.uiState.value
        assertEquals(7, state.scrollToFieldIndex)
    }

    @Test
    fun `onChangeWord should mark invalid words`() = runBlocking {
        whenever(bip39Service.isValidWord("invalid_word")).thenReturn(false)

        viewModel.onChangeWord(3, "invalid_word")

        val state = viewModel.uiState.value
        assertTrue(state.invalidWordIndices.contains(3))
    }

    @Test
    fun `onChangeWord should clear invalid flag when word becomes valid`() = runBlocking {
        whenever(bip39Service.isValidWord("invalid")).thenReturn(false)
        whenever(bip39Service.isValidWord("valid")).thenReturn(true)

        viewModel.onChangeWord(2, "invalid")
        assertTrue(viewModel.uiState.value.invalidWordIndices.contains(2))

        viewModel.onChangeWord(2, "valid")
        assertFalse(viewModel.uiState.value.invalidWordIndices.contains(2))
    }

    // endregion

    // region Paste Handling

    @Test
    fun `handlePastedWords should parse 12 words separated by spaces`() {
        val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("abandon", state.words[0])
        assertEquals("ability", state.words[1])
        assertEquals("accident", state.words[11])
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should parse 24 words separated by spaces`() {
        val words = List(24) { "w${it + 1}" }.joinToString(" ")

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w24", state.words[23])
        assertTrue(state.is24Words)
    }

    @Test
    fun `handlePastedWords should handle multiple whitespace types`() {
        val words = "w1\tw2\nw3  w4\t\nw5 w6 w7 w8 w9 w10 w11 w12"

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w2", state.words[1])
        assertEquals("w3", state.words[2])
    }

    @Test
    fun `handlePastedWords should parse 12 words separated by tabs only`() {
        val words = "w1\tw2\tw3\tw4\tw5\tw6\tw7\tw8\tw9\tw10\tw11\tw12"

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w2", state.words[1])
        assertEquals("w12", state.words[11])
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should parse 24 words separated by tabs only`() {
        val words = List(24) { "w${it + 1}" }.joinToString("\t")

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w24", state.words[23])
        assertTrue(state.is24Words)
    }

    @Test
    fun `handlePastedWords should parse 12 words separated by newlines only`() {
        val words = "w1\nw2\nw3\nw4\nw5\nw6\nw7\nw8\nw9\nw10\nw11\nw12"

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w2", state.words[1])
        assertEquals("w12", state.words[11])
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should parse 24 words separated by newlines only`() {
        val words = List(24) { "w${it + 1}" }.joinToString("\n")

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w24", state.words[23])
        assertTrue(state.is24Words)
    }

    @Test
    fun `handlePastedWords should clear excess slots when pasting 12 words`() {
        // First manually set all 24 words
        for (i in 0 until 24) {
            viewModel.onChangeWord(i, "word$i")
        }

        // Then paste 12 words
        val words = "w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11 w12"
        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertEquals("w12", state.words[11])
        assertEquals("", state.words[12])
        assertEquals("", state.words[23])
    }

    @Test
    fun `handlePastedWords should detect invalid words`() = runBlocking {
        whenever(bip39Service.isValidWord("invalid")).thenReturn(false)
        whenever(bip39Service.isValidWord(any())).thenReturn(true)
        whenever(bip39Service.isValidWord("invalid")).thenReturn(false)

        val words = List(12) { if (it == 2) "invalid" else "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertTrue(state.invalidWordIndices.contains(2))
    }

    @Test
    fun `handlePastedWords should dismiss keyboard when all words valid`() {
        val words = List(12) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertTrue(state.shouldDismissKeyboard)
    }

    @Test
    fun `handlePastedWords should not dismiss keyboard when words invalid`() = runBlocking {
        whenever(bip39Service.isValidWord("invalid")).thenReturn(false)

        val words = List(12) { if (it == 0) "invalid" else "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertFalse(state.shouldDismissKeyboard)
    }

    @Test
    fun `handlePastedWords should keep full replace when pasting 12 words into later field of empty form`() {
        val words = List(12) { "w${it + 1}" }.joinToString(" ")

        viewModel.onChangeWord(5, words)

        val state = viewModel.uiState.value
        assertEquals("w1", state.words[0])
        assertEquals("w12", state.words[11])
        assertEquals("", state.words[12])
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should spread 12 words into field 13 when earlier fields hold words`() {
        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "a${i + 1}")
        }
        val secondHalf = List(12) { "b${it + 13}" }.joinToString(" ")

        viewModel.onChangeWord(12, secondHalf)

        val state = viewModel.uiState.value
        assertEquals(List(12) { "a${it + 1}" }, state.words.subList(0, 12))
        assertEquals(List(12) { "b${it + 13}" }, state.words.subList(12, 24))
        assertTrue(state.is24Words)
        assertNull(state.focusedIndex)
        assertTrue(state.areButtonsEnabled)
    }

    @Test
    fun `handlePastedWords should spread 12 words into later field on empty 24 word layout`() {
        viewModel.onChangeWord(0, List(24) { "a${it + 1}" }.joinToString(" "))
        for (i in 0 until 24) {
            viewModel.onChangeWord(i, "")
        }
        val secondHalf = List(12) { "b${it + 13}" }.joinToString(" ")

        viewModel.onChangeWord(12, secondHalf)

        val state = viewModel.uiState.value
        assertTrue(state.words.subList(0, 12).all { it.isEmpty() })
        assertEquals(List(12) { "b${it + 13}" }, state.words.subList(12, 24))
        assertTrue(state.is24Words)
        assertEquals(0, state.focusedIndex)
    }

    @Test
    fun `handlePastedWords should full replace 12 words pasted into first field on 24 word layout`() {
        viewModel.onChangeWord(0, List(24) { "a${it + 1}" }.joinToString(" "))

        viewModel.onChangeWord(0, List(12) { "b${it + 1}" }.joinToString(" "))

        val state = viewModel.uiState.value
        assertEquals(List(12) { "b${it + 1}" }, state.words.subList(0, 12))
        assertTrue(state.words.subList(12, 24).all { it.isEmpty() })
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should spread fragment from first field`() {
        viewModel.onChangeWord(0, "abandon ability able")

        val state = viewModel.uiState.value
        assertEquals(listOf("abandon", "ability", "able"), state.words.subList(0, 3))
        assertTrue(state.words.drop(3).all { it.isEmpty() })
        assertFalse(state.is24Words)
        assertEquals(3, state.focusedIndex)
        assertEquals(3, state.scrollToFieldIndex)
        assertTrue(state.suggestions.isEmpty())
        assertFalse(state.shouldDismissKeyboard)
    }

    @Test
    fun `handlePastedWords should spread fragment from middle field`() {
        viewModel.onChangeWord(0, "about")
        viewModel.onChangeWord(8, "access")

        viewModel.onChangeWord(5, "abandon ability able")

        val state = viewModel.uiState.value
        assertEquals("about", state.words[0])
        assertEquals("", state.words[4])
        assertEquals(listOf("abandon", "ability", "able"), state.words.subList(5, 8))
        assertEquals("access", state.words[8])
        assertEquals(9, state.focusedIndex)
    }

    @Test
    fun `handlePastedWords should switch to 24 words when fragment passes field 12`() {
        val words = List(15) { "w${it + 1}" }.joinToString(" ")

        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertTrue(state.is24Words)
        assertEquals("w1", state.words[0])
        assertEquals("w15", state.words[14])
        assertEquals("", state.words[15])
        assertEquals(15, state.focusedIndex)
    }

    @Test
    fun `handlePastedWords should drop words beyond field 24`() {
        val words = List(5) { "w${it + 1}" }.joinToString(" ")

        viewModel.onChangeWord(21, words)

        val state = viewModel.uiState.value
        assertTrue(state.is24Words)
        assertEquals(listOf("w1", "w2", "w3"), state.words.subList(21, 24))
        assertEquals(24, state.words.size)
    }

    @Test
    fun `handlePastedWords should mark invalid word in fragment`() = runBlocking {
        whenever(bip39Service.isValidWord("zzzz")).thenReturn(false)

        viewModel.onChangeWord(2, "abandon zzzz able")

        val state = viewModel.uiState.value
        assertEquals("zzzz", state.words[3])
        assertEquals(setOf(3), state.invalidWordIndices)
    }

    @Test
    fun `handlePastedWords should clear invalid flag of overwritten fields`() = runBlocking {
        whenever(bip39Service.isValidWord("zzzz")).thenReturn(false)
        viewModel.onChangeWord(1, "zzzz")
        assertTrue(viewModel.uiState.value.invalidWordIndices.contains(1))

        viewModel.onChangeWord(0, "abandon ability")

        val state = viewModel.uiState.value
        assertEquals("ability", state.words[1])
        assertTrue(state.invalidWordIndices.isEmpty())
    }

    @Test
    fun `handlePastedWords should write word with trailing space to its field`() {
        viewModel.onChangeWord(4, "abandon ")

        val state = viewModel.uiState.value
        assertEquals("abandon", state.words[4])
        assertTrue(state.words.withIndex().filter { it.index != 4 }.all { it.value.isEmpty() })
        assertFalse(state.is24Words)
    }

    @Test
    fun `handlePastedWords should ignore whitespace only input`() {
        viewModel.onChangeWord(0, "abandon")

        viewModel.onChangeWord(0, "  ")

        val state = viewModel.uiState.value
        assertEquals("abandon", state.words[0])
    }

    @Test
    fun `handlePastedWords should focus first empty field when fragment fills the tail`() {
        for (i in 3 until 9) {
            viewModel.onChangeWord(i, "word$i")
        }

        viewModel.onChangeWord(9, "abandon ability able")

        assertEquals(0, viewModel.uiState.value.focusedIndex)
    }

    @Test
    fun `handlePastedWords should clear focus and dismiss keyboard when fragment completes phrase`() {
        for (i in 0 until 9) {
            viewModel.onChangeWord(i, "word$i")
        }

        viewModel.onChangeWord(9, "abandon ability able")

        val state = viewModel.uiState.value
        assertNull(state.focusedIndex)
        assertTrue(state.shouldDismissKeyboard)
        assertTrue(state.areButtonsEnabled)
    }

    // endregion

    // region Focus Management

    @Test
    fun `onChangeWordFocus should set focused index when gaining focus`() {
        viewModel.onChangeWordFocus(5, true)

        assertEquals(5, viewModel.uiState.value.focusedIndex)
    }

    @Test
    fun `onChangeWordFocus should clear focused index when losing focus`() {
        viewModel.onChangeWordFocus(5, true)
        viewModel.onChangeWordFocus(5, false)

        assertNull(viewModel.uiState.value.focusedIndex)
    }

    @Test
    fun `onChangeWordFocus should update scrollToFieldIndex`() {
        viewModel.onChangeWordFocus(8, true)

        assertEquals(8, viewModel.uiState.value.scrollToFieldIndex)
    }

    @Test
    fun `onChangeWordFocus should clear suggestions when blurring`() {
        viewModel.onChangeWordFocus(0, true)
        viewModel.onChangeWordFocus(0, false)

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    // endregion

    // region Suggestions

    @Test
    fun `updateSuggestions should return suggestions for valid input`() = runBlocking {
        whenever(bip39Service.getSuggestions("aba", 3u)).thenReturn(listOf("abandon", "ability", "able"))

        viewModel.onChangeWordFocus(0, true)
        viewModel.onChangeWord(0, "aba")

        val state = viewModel.uiState.value
        assertEquals(3, state.suggestions.size)
    }

    @Test
    fun `updateSuggestions should filter exact matches`() = runBlocking {
        whenever(bip39Service.getSuggestions("abandon", 3u)).thenReturn(listOf("abandon"))

        viewModel.onChangeWordFocus(0, true)
        viewModel.onChangeWord(0, "abandon")

        val state = viewModel.uiState.value
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `onSelectSuggestion should apply suggestion to focused word`() {
        viewModel.onChangeWordFocus(0, true)
        viewModel.onSelectSuggestion("abandon")

        assertEquals("abandon", viewModel.uiState.value.words[0])
    }

    @Test
    fun `onSelectSuggestion should clear suggestions`() {
        viewModel.onChangeWordFocus(0, true)
        viewModel.onSelectSuggestion("abandon")

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    // endregion

    // region Passphrase Management

    @Test
    fun `onAdvancedClick should toggle showingPassphrase`() {
        assertFalse(viewModel.uiState.value.showingPassphrase)

        viewModel.onAdvancedClick()
        assertTrue(viewModel.uiState.value.showingPassphrase)

        viewModel.onAdvancedClick()
        assertFalse(viewModel.uiState.value.showingPassphrase)
    }

    @Test
    fun `onAdvancedClick should clear passphrase when toggling`() {
        viewModel.onAdvancedClick()
        viewModel.onChangePassphrase("test-passphrase")

        viewModel.onAdvancedClick()

        assertEquals("", viewModel.uiState.value.bip39Passphrase)
    }

    @Test
    fun `onChangePassphrase should update passphrase value`() {
        viewModel.onChangePassphrase("my-passphrase-123")

        assertEquals("my-passphrase-123", viewModel.uiState.value.bip39Passphrase)
    }

    // endregion

    // region Navigation

    @Test
    fun `onBackspaceInEmpty should move focus to previous field`() {
        viewModel.onBackspaceInEmpty(5)

        assertEquals(4, viewModel.uiState.value.focusedIndex)
    }

    @Test
    fun `onBackspaceInEmpty at index 0 should not change focus`() {
        viewModel.onBackspaceInEmpty(0)

        assertEquals(0, viewModel.uiState.value.focusedIndex)
    }

    // endregion

    // region UX Flags

    @Test
    fun `onKeyboardDismiss should reset shouldDismissKeyboard flag`() {
        viewModel.onKeyboardDismiss()

        assertFalse(viewModel.uiState.value.shouldDismissKeyboard)
    }

    @Test
    fun `onScrollComplete should reset scrollToFieldIndex`() {
        viewModel.onScrollComplete()

        assertNull(viewModel.uiState.value.scrollToFieldIndex)
    }

    // endregion

    // region Computed Properties

    @Test
    fun `wordCount should return 12 when is24Words is false`() {
        val state = viewModel.uiState.value

        assertFalse(state.is24Words)
        assertEquals(12, state.wordCount)
    }

    @Test
    fun `wordCount should return 24 when is24Words is true`() {
        val words = List(24) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        val state = viewModel.uiState.value
        assertTrue(state.is24Words)
        assertEquals(24, state.wordCount)
    }

    @Test
    fun `wordsPerColumn should return correct values`() {
        val state12 = viewModel.uiState.value
        assertEquals(6, state12.wordsPerColumn)

        val words = List(24) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        val state24 = viewModel.uiState.value
        assertEquals(12, state24.wordsPerColumn)
    }

    @Test
    fun `areButtonsEnabled should be true with valid mnemonic`() {
        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be false with checksum error`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertFalse(viewModel.uiState.value.areButtonsEnabled)
    }

    // endregion

    // region Checksum Error Visibility

    @Test
    fun `checksumErrorVisible should be true with 12 valid BIP39 words but invalid checksum`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertTrue(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be true with 24 valid BIP39 words but invalid checksum`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        val words24 = List(24) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words24)

        assertTrue(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be false when correcting invalid checksum`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }
        assertTrue(viewModel.uiState.value.checksumErrorVisible)

        // Now fix the checksum by mocking success
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.success(Unit))
        viewModel.onChangeWord(11, "corrected")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be false with incomplete mnemonic`() {
        for (i in 0 until 6) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be false when invalid BIP39 words present`() = runBlocking {
        whenever(bip39Service.isValidWord("invalidword")).thenReturn(false)

        for (i in 0 until 11) {
            viewModel.onChangeWord(i, "word$i")
        }
        viewModel.onChangeWord(11, "invalidword")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be true after pasting 12 words with bad checksum`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        val words = List(12) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertTrue(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be true after pasting 24 words with bad checksum`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        val words = List(24) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertTrue(viewModel.uiState.value.checksumErrorVisible)
    }

    @Test
    fun `checksumErrorVisible should be false after pasting valid mnemonic`() {
        val words = List(12) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
    }

    // endregion

    // region Buttons Enabled Reactive Updates

    @Test
    fun `areButtonsEnabled should remain false during progressive word entry`() {
        for (i in 0 until 6) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertFalse(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be true after entering all 12 valid words`() {
        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }

        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be true after entering all 24 valid words`() {
        val words = List(24) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be false when changing valid word to invalid`() = runBlocking {
        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }
        assertTrue(viewModel.uiState.value.areButtonsEnabled)

        whenever(bip39Service.isValidWord("badword")).thenReturn(false)
        viewModel.onChangeWord(5, "badword")

        assertFalse(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be false when valid word causes checksum error`() = runBlocking {
        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }
        assertTrue(viewModel.uiState.value.areButtonsEnabled)

        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))
        viewModel.onChangeWord(11, "different")

        assertFalse(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `areButtonsEnabled should be true after pasting valid 12-word mnemonic`() {
        val words = List(12) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    // endregion

    // region Correction Flows

    @Test
    fun `correction flow - invalid words to checksum error to corrected to enabled`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }
        assertTrue(viewModel.uiState.value.checksumErrorVisible)
        assertFalse(viewModel.uiState.value.areButtonsEnabled)

        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.success(Unit))
        viewModel.onChangeWord(11, "corrected")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `correction flow - paste invalid mnemonic then correct individual words`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        val words = List(12) { "w${it + 1}" }.joinToString(" ")
        viewModel.onChangeWord(0, words)

        assertTrue(viewModel.uiState.value.checksumErrorVisible)
        assertFalse(viewModel.uiState.value.areButtonsEnabled)

        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.success(Unit))
        viewModel.onChangeWord(0, "fixed")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `correction flow - invalid BIP39 word to valid word enables buttons`() = runBlocking {
        whenever(bip39Service.isValidWord("badword")).thenReturn(false)

        for (i in 0 until 11) {
            viewModel.onChangeWord(i, "word$i")
        }
        viewModel.onChangeWord(11, "badword")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
        assertFalse(viewModel.uiState.value.areButtonsEnabled)

        whenever(bip39Service.isValidWord("goodword")).thenReturn(true)
        viewModel.onChangeWord(11, "goodword")

        assertFalse(viewModel.uiState.value.checksumErrorVisible)
        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    // endregion

    // region State Consistency

    @Test
    fun `buttons should always be disabled when checksum error visible`() = runBlocking {
        whenever(bip39Service.validateMnemonic(any())).thenReturn(Result.failure(Exception("Invalid checksum")))

        for (i in 0 until 12) {
            viewModel.onChangeWord(i, "word$i")
        }

        val state = viewModel.uiState.value
        assertTrue(state.checksumErrorVisible)
        assertFalse(state.areButtonsEnabled)
    }

    @Test
    fun `buttons should always be disabled when invalid BIP39 words present`() = runBlocking {
        whenever(bip39Service.isValidWord("invalidword")).thenReturn(false)

        for (i in 0 until 11) {
            viewModel.onChangeWord(i, "word$i")
        }
        viewModel.onChangeWord(11, "invalidword")

        val state = viewModel.uiState.value
        assertFalse(state.checksumErrorVisible)
        assertFalse(state.areButtonsEnabled)
        assertTrue(state.invalidWordIndices.contains(11))
    }

    // endregion
}
