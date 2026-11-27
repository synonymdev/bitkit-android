package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class RequestOrPaySheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val contact = PubkyProfile(
        publicKey = "pk8e3qm5gxag",
        name = "John Carvalho",
        bio = "",
        imageUrl = null,
        links = emptyList(),
        status = null,
    )

    @Test
    fun compactActionsAreTaggedClickableAndOnScreen() {
        var paid = false
        var requested = false
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)
            ) {
                AppThemeSurface {
                    RequestOrPaySheetContent(
                        contact = contact,
                        isPayLoading = false,
                        canRequest = true,
                        onPay = { paid = true },
                        onRequest = { requested = true },
                        modifier = Modifier.size(320.dp, 560.dp)
                    )
                }
            }
        }
        val pay = composeTestRule.onNodeWithTag("RequestOrPayPay").assertHasClickAction().assertIsEnabled()
        val request = composeTestRule.onNodeWithTag("RequestOrPayRequest").assertHasClickAction().assertIsEnabled()
        val payBounds = pay.getUnclippedBoundsInRoot()
        val requestBounds = request.getUnclippedBoundsInRoot()
        assertEquals(payBounds.bottom - payBounds.top, requestBounds.bottom - requestBounds.top)
        assertTrue(requestBounds.bottom <= 560.dp)
        pay.performClick()
        request.performClick()
        composeTestRule.runOnIdle {
            assertTrue(paid)
            assertTrue(requested)
        }
    }

    @Test
    fun loadingPayDisablesBothActions() {
        composeTestRule.setContent {
            AppThemeSurface {
                RequestOrPaySheetContent(
                    contact = contact,
                    isPayLoading = true,
                    canRequest = true,
                    onPay = {},
                    onRequest = {},
                    modifier = Modifier.size(320.dp, 640.dp)
                )
            }
        }
        composeTestRule.onNodeWithTag("RequestOrPayPay").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("RequestOrPayRequest").assertIsNotEnabled()
    }
}
