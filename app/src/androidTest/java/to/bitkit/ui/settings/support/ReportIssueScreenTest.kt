package to.bitkit.ui.settings.support

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import to.bitkit.ui.theme.AppThemeSurface

class ReportIssueContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testUiState = ReportIssueUiState(
        emailInput = "",
        messageInput = "",
        errorEmail = false,
        isSendEnabled = false,
        isLoading = false
    )

    @Test
    fun whenScreenLoaded_shouldShowAllComponents() {
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.SCREEN).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.TITLE).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.DESCRIPTION).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.EMAIL_LABEL).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.EMAIL_INPUT).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.MESSAGE_LABEL).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.MESSAGE_INPUT).assertExists()
        composeTestRule.onNodeWithTag(ReportIssueTestTags.SEND_BUTTON).assertExists()
    }

    @Test
    fun whenEmailInputChanges_shouldTriggerCallback() {
        var emailUpdated = ""
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = { emailUpdated = it },
                    onUpdateMessage = {},
                    uiState = testUiState.copy(emailInput = "")
                )
            }
        }

        val testEmail = "test@example.com"
        composeTestRule.onNodeWithTag(ReportIssueTestTags.EMAIL_INPUT)
            .performTextInput(testEmail)

        assert(emailUpdated == testEmail)
    }

    @Test
    fun whenMessageInputChanges_shouldTriggerCallback() {
        var messageUpdated = ""
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = { messageUpdated = it },
                    uiState = testUiState.copy(messageInput = "")
                )
            }
        }

        val testMessage = "Test issue description"
        composeTestRule.onNodeWithTag(ReportIssueTestTags.MESSAGE_INPUT)
            .performTextInput(testMessage)

        assert(messageUpdated == testMessage)
    }

    @Test
    fun whenSendButtonClicked_shouldTriggerCallback() {
        var sendClicked = false
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = { sendClicked = true },
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState.copy(isSendEnabled = true)
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.SEND_BUTTON)
            .performClick()

        assert(sendClicked)
    }

    @Test
    fun whenFormInvalid_sendButtonShouldBeDisabled() {
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState.copy(isSendEnabled = false)
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.SEND_BUTTON)
            .assertIsNotEnabled()
    }

    @Test
    fun whenFormValid_sendButtonShouldBeEnabled() {
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState.copy(
                        emailInput = "valid@email.com",
                        messageInput = "Valid message",
                        isSendEnabled = true
                    )
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.SEND_BUTTON)
            .assertIsEnabled()
    }

    @Test
    fun whenLoading_sendButtonShouldShowLoadingState() {
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = {},
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState.copy(
                        isLoading = true,
                        isSendEnabled = true
                    )
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.SEND_BUTTON)
            .assertIsNotEnabled()
    }

    @Test
    fun whenBackButtonClicked_shouldTriggerCallback() {
        var backClicked = false
        composeTestRule.setContent {
            AppThemeSurface {
                ReportIssueContent(
                    onBack = { backClicked = true },
                    onClose = {},
                    onConfirm = {},
                    onUpdateEmail = {},
                    onUpdateMessage = {},
                    uiState = testUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(ReportIssueTestTags.TITLE).performClick()
        assert(backClicked)
    }
}
