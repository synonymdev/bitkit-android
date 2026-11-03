package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.Toast
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.LocalBottomSheetOverlayState
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@HiltAndroidTest
@ComposeUi
@OptIn(ExperimentalMaterial3Api::class)
class BottomSheetOverlayHostTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun dismissedSheetIsRemovedFromOverlayHost() {
        val overlayState = BottomSheetOverlayState()
        val showSheet = mutableStateOf(true)
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalBottomSheetOverlayState provides overlayState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showSheet.value) {
                            BottomSheet(onDismissRequest = { showSheet.value = false }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .testTag("OverlaySheet")
                                )
                            }
                        }
                        BottomSheetOverlayHost(state = overlayState)
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("OverlaySheet").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(1, overlayState.entries.size) }

        composeTestRule.onRoot().performTouchInput { click(Offset(center.x, 50f)) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("OverlaySheet").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(overlayState.entries.isEmpty()) }
    }

    @Test
    fun toastAboveRegisteredSheetDismissesOnlyUpward() {
        val overlayState = BottomSheetOverlayState()
        var dismissCount = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalBottomSheetOverlayState provides overlayState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        BottomSheet(onDismissRequest = {}) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                                    .testTag("OverlaySheet")
                            )
                        }
                        BottomSheetOverlayHost(state = overlayState)
                        ToastView(
                            toast = Toast(
                                type = Toast.ToastType.INFO,
                                title = "Toast",
                                autoHide = false,
                                testTag = "OverlayToast",
                            ),
                            onDismiss = { dismissCount++ },
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("OverlaySheet").assertIsDisplayed()
        val toast = composeTestRule.onNodeWithTag("OverlayToast").assertIsDisplayed()

        toast.performTouchInput {
            swipe(start = center, end = Offset(center.x + 300f, center.y))
        }
        composeTestRule.waitForIdle()
        assertEquals(0, dismissCount)

        toast.performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 300f))
        }
        composeTestRule.waitForIdle()
        assertEquals(0, dismissCount)

        toast.performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 300f))
        }
        composeTestRule.waitForIdle()

        assertEquals(1, dismissCount)
        composeTestRule.onNodeWithTag("OverlaySheet").assertIsDisplayed()
    }
}
