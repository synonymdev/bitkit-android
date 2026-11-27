package to.bitkit.wear.ui.screens.restore

import app.cash.turbine.test
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.security.FakeBip39
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoreViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val valid = List(11) { "abandon" } + "about"

    private lateinit var sut: RestoreViewModel

    @Before
    fun setUp() {
        sut = RestoreViewModel(FakeBip39(), walletRepo)
    }

    @Test
    fun `picking a length opens the first word and other lengths are refused`() = test {
        sut.effects.test {
            sut.onWordCountSelect(18)
            expectNoEvents()

            sut.onWordCountSelect(24)
            assertEquals(RestoreEffect.ShowWord(0), awaitItem())
        }
        assertEquals(24, sut.uiState.value.entries.size)
    }

    @Test
    fun `typing sanitizes the input and suggests words after two letters`() = test {
        sut.onWordCountSelect(12)

        sut.onWordChange(0, "A")
        assertEquals(WordEntry(input = "a"), sut.uiState.value.entries[0])

        sut.onWordChange(0, " Ab1 ")
        val entry = sut.uiState.value.entries[0]
        assertEquals("ab", entry.input)
        assertEquals(listOf("abandon", "ability", "able", "about", "above"), entry.suggestions)
        assertFalse(entry.isWord)

        sut.onWordChange(0, "act")
        assertTrue(sut.uiState.value.entries[0].isWord)
        assertEquals(listOf("act", "action"), sut.uiState.value.entries[0].suggestions)

        sut.onWordChange(0, "xy")
        assertTrue(sut.uiState.value.entries[0].suggestions.isEmpty())
    }

    @Test
    fun `a word that is not in the wordlist cannot be confirmed`() = test {
        sut.onWordCountSelect(12)
        sut.effects.test {
            assertEquals(RestoreEffect.ShowWord(0), awaitItem())
            sut.onWordChange(0, "aban")
            sut.onWordConfirm(0)
            sut.onWordConfirm(99)

            expectNoEvents()
        }
    }

    @Test
    fun `picking a suggestion accepts it and opens the next word`() = test {
        sut.onWordCountSelect(12)
        sut.effects.test {
            assertEquals(RestoreEffect.ShowWord(0), awaitItem())
            sut.onWordChange(0, "ab")
            sut.onSuggestionSelect(0, "abandon")

            assertEquals(RestoreEffect.ShowWord(1), awaitItem())
        }
        assertEquals("abandon", sut.uiState.value.entries[0].input)
    }

    @Test
    fun `a valid phrase opens the passphrase step after the last word`() = test {
        sut.effects.test {
            enter(valid)

            assertEquals(RestoreEffect.ShowPassphrase, expectMostRecentItem())
        }
    }

    @Test
    fun `a checksum failure is reported and fixing a word on the way back passes`() = test {
        sut.effects.test {
            enter(List(12) { "abandon" })
            assertEquals(RestoreEffect.ShowChecksumError, expectMostRecentItem())

            // Back to an earlier word: its input is still there and the later words are kept.
            assertEquals("abandon", sut.uiState.value.entries[3].input)
            sut.onWordChange(3, "zoo")
            sut.onWordConfirm(3)
            assertEquals(RestoreEffect.ShowWord(4), awaitItem())
            assertEquals("abandon", sut.uiState.value.entries[4].input)

            sut.onSuggestionSelect(11, "about")
            assertEquals(RestoreEffect.ShowPassphrase, awaitItem())
        }
    }

    @Test
    fun `picking the same length again keeps the words and another length clears them`() = test {
        sut.onWordCountSelect(12)
        sut.onSuggestionSelect(0, "abandon")

        sut.onWordCountSelect(12)
        assertEquals("abandon", sut.uiState.value.entries[0].input)

        sut.onWordCountSelect(24)
        assertTrue(sut.uiState.value.entries.all { it.input.isEmpty() })
    }

    @Test
    fun `skipping the passphrase saves the phrase without one and clears the words`() = test {
        whenever(walletRepo.restore(any())).thenReturn(Result.success(Unit))
        sut.effects.test {
            enter(valid)
            sut.onPassphraseSkip()

            assertEquals(RestoreEffect.Restored, expectMostRecentItem())
        }
        verify(walletRepo).restore(WalletSeed(valid.joinToString(" "), null))
        assertEquals(RestoreUiState(), sut.uiState.value)
    }

    @Test
    fun `a typed passphrase is saved as typed and an empty one as none`() = test {
        whenever(walletRepo.restore(any())).thenReturn(Result.success(Unit))
        enter(valid)
        sut.onPassphraseAdd()
        assertTrue(sut.uiState.value.isPassphraseEditing)
        sut.onPassphraseChange(" Tr ezor ")
        sut.onPassphraseConfirm()
        verify(walletRepo).restore(WalletSeed(valid.joinToString(" "), " Tr ezor "))

        enter(valid)
        sut.onPassphraseAdd()
        sut.onPassphraseConfirm()
        verify(walletRepo).restore(WalletSeed(valid.joinToString(" "), null))
    }

    @Test
    fun `saving is refused while the phrase is invalid`() = test {
        sut.effects.test {
            enter(List(12) { "abandon" })
            sut.onPassphraseSkip()

            assertEquals(RestoreEffect.ShowChecksumError, expectMostRecentItem())
        }
        verify(walletRepo, never()).restore(any())
    }

    @Test
    fun `a failed save keeps the words and reports why`() = test {
        enter(valid)
        whenever(walletRepo.restore(any())).thenReturn(Result.failure(SeedStoreError.Failed()))

        sut.onPassphraseSkip()

        assertTrue(sut.uiState.value.isSaveFailed)
        assertFalse(sut.uiState.value.isSaving)
        assertEquals("about", sut.uiState.value.entries[11].input)

        whenever(walletRepo.restore(any())).thenReturn(Result.success(Unit))
        sut.onPassphraseSkip()
        assertEquals(RestoreUiState(), sut.uiState.value)
    }

    @Test
    fun `saving without words returns to the start instead of saving`() = test {
        sut.effects.test {
            sut.onPassphraseSkip()
            assertEquals(RestoreEffect.ShowStart, awaitItem())

            sut.onPassphraseConfirm()
            assertEquals(RestoreEffect.ShowStart, awaitItem())
        }
        verify(walletRepo, never()).restore(any())
        assertFalse(sut.uiState.value.isSaving)
    }

    @Test
    fun `a word screen without words returns to the start`() = test {
        sut.effects.test {
            sut.onEntriesMissing()
            assertEquals(RestoreEffect.ShowStart, awaitItem())
        }
    }

    @Test
    fun `the state never prints the words or the passphrase`() = test {
        enter(valid)
        sut.onPassphraseChange("hunter")

        val printed = "${sut.uiState.value} ${sut.uiState.value.entries}"

        assertFalse("abandon" in printed)
        assertFalse("hunter" in printed)
    }

    private fun enter(words: List<String>) {
        sut.onWordCountSelect(words.size)
        words.forEachIndexed { index, word ->
            sut.onWordChange(index, word)
            sut.onWordConfirm(index)
        }
    }
}
