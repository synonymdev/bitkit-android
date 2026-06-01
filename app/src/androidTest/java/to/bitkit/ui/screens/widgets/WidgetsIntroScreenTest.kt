package to.bitkit.ui.screens.widgets

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class WidgetsIntroScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testWidgetsIntroActions() {
        var viewOrganizeClicked = false
        var addWidgetClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WidgetsIntroScreen(
                    onViewOrganize = { viewOrganizeClicked = true },
                    onAddWidget = { addWidgetClicked = true },
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetsOnboardingViewOrganize").assertExists().performClick()
        assert(viewOrganizeClicked)

        composeTestRule.onNodeWithTag("WidgetsOnboardingAddWidget").assertExists().performClick()
        assert(addWidgetClicked)
    }

    @Test
    fun testWidgetsIntroActionsAreHorizontal() {
        composeTestRule.setContent {
            AppThemeSurface {
                WidgetsIntroScreen(
                    onViewOrganize = {},
                    onAddWidget = {},
                    onBackClick = {},
                )
            }
        }

        val viewOrganizeBounds = composeTestRule
            .onNodeWithTag("WidgetsOnboardingViewOrganize")
            .getUnclippedBoundsInRoot()
        val addWidgetBounds = composeTestRule
            .onNodeWithTag("WidgetsOnboardingAddWidget")
            .getUnclippedBoundsInRoot()

        assertTrue(viewOrganizeBounds.right < addWidgetBounds.left)
        assertTrue(viewOrganizeBounds.top < addWidgetBounds.bottom)
        assertTrue(addWidgetBounds.top < viewOrganizeBounds.bottom)
    }
}
