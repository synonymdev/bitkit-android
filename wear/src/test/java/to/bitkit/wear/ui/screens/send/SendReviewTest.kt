package to.bitkit.wear.ui.screens.send

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.screens.home.DisplayAmount
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The pay button of the review, as a screen reader and a plain tap reach it. */
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round")
@RunWith(RobolectricTestRunner::class)
class SendReviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var pays = 0

    @Test
    fun `a tap on the pay button never pays`() {
        setReview(isHoldEnabled = true)

        composeRule.onNodeWithTag("WatchSendHold").performClick()
        composeRule.onNodeWithTag("WatchSendHold").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(0, pays)
    }

    @Test
    fun `a screen reader pays by the named action or by a long click`() {
        setReview(isHoldEnabled = true)
        val config = composeRule.onNodeWithTag("WatchSendHold").fetchSemanticsNode().config

        val action = assertNotNull(config.getOrNull(SemanticsActions.CustomActions)).single()
        assertEquals("Pay", action.label)
        assertEquals("Pay", config.getOrNull(SemanticsActions.OnLongClick)?.label)

        composeRule.runOnUiThread { action.action() }
        assertEquals(1, pays)

        composeRule.onNodeWithTag("WatchSendHold").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(2, pays)
    }

    @Test
    fun `a screen reader has no way to pay while the hold is off`() {
        setReview(isHoldEnabled = false)
        val node = composeRule.onNodeWithTag("WatchSendHold")
        val config = node.fetchSemanticsNode().config

        node.assertIsNotEnabled()
        assertNull(config.getOrNull(SemanticsActions.CustomActions))
        assertNull(config.getOrNull(SemanticsActions.OnLongClick))
        assertNotNull(config.getOrNull(SemanticsProperties.Disabled))
        assertEquals(0, pays)
    }

    private fun setReview(isHoldEnabled: Boolean) {
        composeRule.setContent {
            WearPreview {
                Review(
                    step = SendStep.Review(
                        amount = DisplayAmount(symbol = "₿", text = "1 000"),
                        fiat = null,
                        fee = null,
                        payee = null,
                    ),
                    isHoldEnabled = isHoldEnabled,
                    isOffline = false,
                    isConnecting = !isHoldEnabled,
                    onHoldComplete = { pays++ },
                )
            }
        }
    }
}
