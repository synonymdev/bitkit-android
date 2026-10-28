package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class PrimaryReceiveConfirmLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun compactSheetKeepsApprovalVisibleAndTouchable() {
        var approvals = 0
        setContent(onConfirm = { approvals++ })

        assertActionInsideViewport("PrimaryReceiveApprove")
        composeTestRule.onNodeWithTag("PrimaryReceiveMinimum").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("PrimaryReceiveApprove").performTouchInput { click() }

        composeTestRule.runOnIdle { assertEquals(1, approvals) }
    }

    @Test
    fun longErrorScrollsWhileRetryAndApprovalStayInsideCompactSheet() {
        var refreshes = 0
        var approvals = 0
        setContent(
            state = PrimaryReceiveUiState(
                offer = OFFER,
                error = "The primary node could not create this invoice. Check your connection and request a new quote. " +
                    "Review its maximum receive fee before you try again.",
            ),
            onConfirm = { approvals++ },
            onRefresh = { refreshes++ },
            fontScale = 1.3f,
        )

        assertActionInsideViewport("PrimaryReceiveRefresh")
        assertActionInsideViewport("PrimaryReceiveApprove")
        val retryBounds = composeTestRule.onNodeWithTag("PrimaryReceiveRefresh").getUnclippedBoundsInRoot()
        val approveBounds = composeTestRule.onNodeWithTag("PrimaryReceiveApprove").getUnclippedBoundsInRoot()
        assertTrue(retryBounds.bottom < approveBounds.top)
        composeTestRule.onNodeWithTag("PrimaryReceiveError").performScrollTo().assertIsDisplayed()
        assertEquals(retryBounds, composeTestRule.onNodeWithTag("PrimaryReceiveRefresh").getUnclippedBoundsInRoot())
        assertEquals(approveBounds, composeTestRule.onNodeWithTag("PrimaryReceiveApprove").getUnclippedBoundsInRoot())
        composeTestRule.onNodeWithTag("PrimaryReceiveApprove").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("PrimaryReceiveRefresh").performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(0, approvals)
        }
    }

    private fun setContent(
        state: PrimaryReceiveUiState = PrimaryReceiveUiState(offer = OFFER),
        onConfirm: () -> Unit = {},
        onRefresh: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.requiredSize(360.dp, 520.dp).testTag("ReceiveConfirmViewport")) {
                        PrimaryReceiveConfirmContent(
                            state = state,
                            onConfirm = onConfirm,
                            onRefresh = onRefresh,
                            onBack = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertActionInsideViewport(tag: String) {
        val viewport = composeTestRule.onNodeWithTag("ReceiveConfirmViewport").getUnclippedBoundsInRoot()
        val action = composeTestRule.onNodeWithTag(tag)
        action.assertIsDisplayed()
        val bounds = action.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
    }

    private companion object {
        val OFFER = PrimaryReceiveOffer(200_000u, 125_500u, "", "")
    }
}
