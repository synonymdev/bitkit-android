package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class IntroArtLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w800dp-h1280dp-port")
    @Test
    fun `portrait tablet shows the button without scrolling`() {
        setContent(fontScale = 1f)

        composeRule.onNodeWithTag(BUTTON_TAG).assertIsDisplayed()
    }

    @Config(sdk = [34], qualifiers = "w600dp-h660dp-port")
    @Test
    fun `portrait tablet with large text scrolls to the button`() {
        setContent(fontScale = 2f)

        composeRule.onNodeWithTag(BUTTON_TAG).performScrollTo().assertIsDisplayed()
    }

    @Config(sdk = [34], qualifiers = "w1000dp-h640dp-land")
    @Test
    fun `landscape tablet with large text scrolls to the button`() {
        setContent(fontScale = 2f)

        composeRule.onNodeWithTag(BUTTON_TAG).performScrollTo().assertIsDisplayed()
    }

    private fun setContent(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppThemeSurface {
                    IntroArtLayout(
                        art = R.drawable.coin_stack_x,
                        title = AnnotatedString("Transfer to spending"),
                        text = "Transfer funds to your spending balance to enjoy instant and cheap transactions " +
                            "with friends, family, and merchants.",
                        buttonText = "Get Started",
                        buttonTestTag = BUTTON_TAG,
                        onButtonClick = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val BUTTON_TAG = "IntroButton"
    }
}
