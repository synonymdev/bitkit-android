package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.theme.AppThemeSurface

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class DrawerMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w960dp-h600dp-land")
    @Test
    fun `short landscape tablet scrolls to settings and app status`() {
        composeRule.setContent {
            // Preview mode lets AppStatus render without the app view model
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    Box {
                        DrawerMenu(
                            drawerState = rememberDrawerState(initialValue = DrawerValue.Open),
                            rootNavController = rememberNavController(),
                            hasSeenWidgetsIntro = true,
                            hasSeenShopIntro = true,
                            onBeforeNavigate = {},
                            showWidgets = true,
                            profileIdentityExists = flowOf(true),
                            isPaykitEnabled = true,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("DrawerSubscriptions").assertIsDisplayed()
        composeRule.onNodeWithTag("DrawerSettings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("DrawerAppStatus").performScrollTo().assertIsDisplayed()
    }
}
