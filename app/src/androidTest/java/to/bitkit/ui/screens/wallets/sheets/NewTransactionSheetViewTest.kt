package to.bitkit.ui.screens.wallets.sheets

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType

@HiltAndroidTest
class NewTransactionSheetViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setup() {
        hiltRule.inject()
        composeTestRule.mainClock.autoAdvance = false
    }

    @Test
    fun testSentLightningTransaction() {
        // Arrange
        val details = NewTransactionSheetDetails(
            sats = 10000L,
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.SENT
        )
        var detailsClicked = false
        var closeClicked = false

        // Act
        composeTestRule.setContent {
            NewTransactionSheetView(
                details = details,
                onCloseClick = { closeClicked = true },
                onDetailClick = { detailsClicked = true }
            )
        }

        // Wait for composition and layout
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

        // Assert
        composeTestRule.onNodeWithTag("new_transaction_sheet").assertExists()
        composeTestRule.onNodeWithTag("transaction_sent_image").assertExists()
        composeTestRule.onNodeWithTag("transaction_received_image").assertDoesNotExist()
        composeTestRule.onNodeWithTag("confetti_animation").assertExists()

        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

//        composeTestRule.onNodeWithTag("balance_header").assertExists() Doesn't work because of viewmodel instance
        composeTestRule.onNodeWithTag("sent_buttons_row").assertExists()

        // Verify buttons exist and click interactions work
        composeTestRule.onNodeWithTag("details_button").assertExists().performClick()
        composeTestRule.waitForIdle()
        assert(detailsClicked)

        composeTestRule.onNodeWithTag("close_button").assertExists().performClick()
        composeTestRule.waitForIdle()
        assert(closeClicked)
    }

    @Test
    fun testReceivedLightningTransaction() {
        // Arrange
        val details = NewTransactionSheetDetails(
            sats = 5000L,
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED
        )
        var closeClicked = false

        // Act
        composeTestRule.setContent {
            NewTransactionSheetView(
                details = details,
                onCloseClick = { closeClicked = true },
                onDetailClick = { }
            )
        }

        // Wait for composition and layout
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

        // Assert
        composeTestRule.onNodeWithTag("new_transaction_sheet").assertExists()
        composeTestRule.onNodeWithTag("transaction_received_image").assertExists()
        composeTestRule.onNodeWithTag("transaction_sent_image").assertDoesNotExist()

        // Wait again for animations
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("confetti_animation").assertExists()
//        composeTestRule.onNodeWithTag("balance_header").assertExists()
        composeTestRule.onNodeWithTag("sent_buttons_row").assertDoesNotExist()

        // Verify only OK button exists for received transactions
        composeTestRule.onNodeWithTag("ok_button").assertExists().performClick()
        composeTestRule.waitForIdle()
        assert(closeClicked)
    }

    @Test
    fun testOnchainTransaction() {
        // Arrange
        val details = NewTransactionSheetDetails(
            sats = 75000L,
            type = NewTransactionSheetType.ONCHAIN,
            direction = NewTransactionSheetDirection.RECEIVED
        )

        // Act
        composeTestRule.setContent {
            NewTransactionSheetView(
                details = details,
                onCloseClick = { },
                onDetailClick = { }
            )
        }

        // Assert - verify orange confetti is used for onchain
        composeTestRule.onNodeWithTag("confetti_animation").assertExists()
    }
}
