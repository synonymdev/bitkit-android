package to.bitkit.ui.settings.backups

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class BackupIntroScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBackupIntroScreenWithFunds() {
        // Arrange
        var closeClicked = false
        var confirmClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BackupIntroScreen(
                    hasFunds = true,
                    onClose = { closeClicked = true },
                    onConfirm = { confirmClicked = true }
                )
            }
        }

        // Assert
        composeTestRule.onNodeWithTag("BackupIntroView").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewImage").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewTitle").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("BackupIntroViewButtons").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewCancel").assertExists().performClick()
        assert(closeClicked)

        composeTestRule.onNodeWithTag("BackupIntroViewContinue").assertExists().performClick()
        assert(confirmClicked)
    }

    @Test
    fun testBackupIntroScreenWithoutFunds() {
        // Arrange
        var closeClicked = false
        var confirmClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BackupIntroScreen(
                    hasFunds = false,
                    onClose = { closeClicked = true },
                    onConfirm = { confirmClicked = true }
                )
            }
        }

        // Assert
        composeTestRule.onNodeWithTag("backup_intro_screen").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("later_button").assertExists().performClick()
        assert(closeClicked)

        composeTestRule.onNodeWithTag("backup_button").assertExists().performClick()
        assert(confirmClicked)
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                BackupIntroScreen(
                    hasFunds = true,
                    onClose = {},
                    onConfirm = {}
                )
            }
        }

        // Assert
        composeTestRule.onNodeWithTag("BackupIntroView").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewImage").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewTitle").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewDescription").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewButtons").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewCancel").assertExists()
        composeTestRule.onNodeWithTag("BackupIntroViewContinue").assertExists()
    }
}
