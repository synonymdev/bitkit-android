package to.bitkit.viewmodels

import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Defaults
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class TagsViewModelTest : BaseUnitTest() {
    private lateinit var sut: TagsViewModel

    private val settingsStore = mock<SettingsStore>()

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        sut = TagsViewModel(settingsStore)
    }

    @Test
    fun `onInputUpdated truncates input over the tag max length`() {
        sut.onInputUpdated("a".repeat(Defaults.TAG_MAX_LENGTH + 5))

        assertEquals("a".repeat(Defaults.TAG_MAX_LENGTH), sut.uiState.value.tagInput)
    }

    @Test
    fun `onInputUpdated replaces line breaks with spaces`() {
        sut.onInputUpdated("coffee\nshop\r\nbar\rtea")

        assertEquals("coffee shop bar tea", sut.uiState.value.tagInput)
    }

    @Test
    fun `onInputUpdated keeps short input untouched`() {
        sut.onInputUpdated("Coffee")

        assertEquals("Coffee", sut.uiState.value.tagInput)
    }

    // A trim per keystroke would stop the space between two words from ever landing
    @Test
    fun `onInputUpdated keeps the space typed between two words`() {
        sut.onInputUpdated("coffee ")

        assertEquals("coffee ", sut.uiState.value.tagInput)
        assertEquals("coffee", sut.uiState.value.confirmedTag)
    }

    @Test
    fun `confirmedTag drops the space the sanitizer leaves for a trailing line break`() {
        sut.onInputUpdated("coffee\nshop\n")

        assertEquals("coffee shop ", sut.uiState.value.tagInput)
        assertEquals("coffee shop", sut.uiState.value.confirmedTag)
    }

    @Test
    fun `confirmedTag drops the leading space of a tag pasted after a line break`() {
        sut.onInputUpdated("\ncoffee shop")

        assertEquals("coffee shop", sut.uiState.value.confirmedTag)
    }

    @Test
    fun `confirmedTag stays empty for whitespace only input`() {
        sut.onInputUpdated("   ")

        assertEquals("", sut.uiState.value.confirmedTag)
    }
}
