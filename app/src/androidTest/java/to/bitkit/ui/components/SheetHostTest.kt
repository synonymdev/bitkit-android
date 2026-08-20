package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@ComposeUi
class SheetHostTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun disabledDismissalBlocksBackDragAndScrim() {
        var dismissCount = 0
        var backgroundClickCount = 0
        composeTestRule.setContent {
            AppThemeSurface {
                SheetHost(
                    shouldExpand = true,
                    onDismiss = { dismissCount++ },
                    dismissEnabled = false,
                    sheets = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .testTag("LockedSheet")
                        )
                    },
                    content = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable { backgroundClickCount++ }
                        )
                    },
                )
            }
        }
        composeTestRule.onNodeWithTag("LockedSheet").assertIsDisplayed()

        pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("LockedSheet").assertIsDisplayed()

        composeTestRule.onNodeWithTag("LockedSheet").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("LockedSheet").assertIsDisplayed()

        composeTestRule.onRoot().performTouchInput { click(Offset(center.x, 50f)) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("LockedSheet").assertIsDisplayed()
        assertEquals(0, dismissCount)
        assertEquals(0, backgroundClickCount)
    }
}
