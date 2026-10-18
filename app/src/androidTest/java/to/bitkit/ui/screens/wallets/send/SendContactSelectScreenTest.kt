package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class SendContactSelectScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun resolvingContactShowsProgressAndPreventsAnotherSelection() {
        val state = mutableStateOf(
            SendContactSelectUiState(
                contacts = persistentListOf(PubkyProfile.placeholder("alice"), PubkyProfile.placeholder("bob")),
                resolvingPublicKey = "alice",
            )
        )
        var selected: String? = null
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendContactSelectContent(uiState = state.value, onContactClick = { selected = it })
                }
            }
        }
        composeTestRule.onNodeWithTag("SendContactLoading_alice", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendContactLoading_bob", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("SendContact_alice").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        )
        composeTestRule.onNodeWithTag("SendContact_bob").assertIsNotEnabled().assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo)
        )
        composeTestRule.runOnIdle { state.value = state.value.copy(resolvingPublicKey = null) }
        composeTestRule.onNodeWithTag("SendContactLoading_alice", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("SendContact_bob").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals("bob", selected) }
    }
}
