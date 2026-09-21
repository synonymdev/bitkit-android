package to.bitkit.ui.screens.wallets.receive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ComposeUi
class EditInvoiceContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hardwareInvoiceContinuesOnchainWithoutTags() {
        var updatedAmount: ULong? = null
        var regularContinueCalled = false

        composeTestRule.setContent {
            AppThemeSurface {
                EditInvoiceContent(
                    amountInputViewModel = previewAmountInputViewModel(sats = 12_345),
                    noteText = "Hardware payment",
                    isSoftKeyboardVisible = false,
                    onchainOnly = true,
                    keyboardVisible = false,
                    tags = persistentListOf("Hardware"),
                    onBack = {},
                    onContinueKeyboard = {},
                    onClickBalance = {},
                    onContinueGeneral = { regularContinueCalled = true },
                    onContinueOnchain = { updatedAmount = it },
                    onClickAddTag = {},
                    onTextChanged = {},
                    onClickTag = {},
                    offlineReceive = OfflineReceiveUiState(amountSats = 12_345uL, isAvailable = true),
                )
            }
        }

        composeTestRule.onNodeWithTag("TagsAdd").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ReceiveOffline").assertDoesNotExist()
        composeTestRule.onNodeWithTag("Tag-Hardware").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ShowQrReceive").performClick()

        assertEquals(12_345uL, updatedAmount)
        assertFalse(regularContinueCalled)
    }

    @Test
    fun offlineReceiveCheckboxSubmitsSelectionWhenAvailable() {
        var selected = false
        composeTestRule.setContent {
            AppThemeSurface {
                EditInvoiceContent(
                    amountInputViewModel = previewAmountInputViewModel(sats = 1_000),
                    noteText = "",
                    isSoftKeyboardVisible = false,
                    keyboardVisible = false,
                    tags = persistentListOf(),
                    onBack = {},
                    onContinueKeyboard = {},
                    onClickBalance = {},
                    onContinueGeneral = {},
                    onClickAddTag = {},
                    onTextChanged = {},
                    onClickTag = {},
                    offlineReceive = OfflineReceiveUiState(amountSats = 1_000uL, isAvailable = true),
                    onClickReceiveOffline = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("ReceiveOffline").assertIsDisplayed().assertIsOff().performClick()
        assertTrue(selected)
    }
}
