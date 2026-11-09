package to.bitkit.ui.screens.transfer

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

/** Blocktank no longer sells channels, so Manual Setup is the only funding option that responds. */
@HiltAndroidTest
@ComposeUi
class FundingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private var manualClicks = 0

    @Before
    fun setup() {
        hiltRule.inject()
        manualClicks = 0
        composeTestRule.setContent {
            AppThemeSurface {
                FundingScreen(onManual = { manualClicks++ })
            }
        }
    }

    @Test
    fun manualSetupIsTheOnlyEnabledOption() {
        composeTestRule.onNodeWithTag("FundManual").assertIsEnabled()
        composeTestRule.onNodeWithTag("FundTransfer").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("FundHardware").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("FundReceive").assertIsNotEnabled()
    }

    @Test
    fun manualSetupTapInvokesOnManual() {
        composeTestRule.onNodeWithTag("FundManual").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, manualClicks)
    }
}
