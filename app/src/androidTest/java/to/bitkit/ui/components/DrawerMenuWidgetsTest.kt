package to.bitkit.ui.components

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.Routes
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@ComposeUi
class DrawerMenuWidgetsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testUnseenWidgetsIntroNavigatesToIntro() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            val drawerState = rememberDrawerState(DrawerValue.Open)

            DrawerMenuTestSurface {
                NavHost(
                    navController = navController,
                    startDestination = Routes.Home,
                ) {
                    composable<Routes.Home> {
                        Text("Home", modifier = Modifier.testTag("HomeRoute"))
                    }
                    composable<Routes.WidgetsIntro> {
                        Text("Widgets Intro", modifier = Modifier.testTag("WidgetsIntroRoute"))
                    }
                }
                DrawerMenu(
                    drawerState = drawerState,
                    rootNavController = navController,
                    hasSeenWidgetsIntro = false,
                    hasSeenShopIntro = true,
                    onBeforeNavigate = {},
                    showWidgets = true,
                )
            }
        }

        composeTestRule.onNodeWithTag("DrawerWidgets").performClick()

        composeTestRule.onNodeWithTag("WidgetsIntroRoute").assertIsDisplayed()
    }

    @Test
    fun testSeenWidgetsIntroRequestsHomeWidgetsPage() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            val drawerState = rememberDrawerState(DrawerValue.Open)
            val openWidgetsHome = remember { mutableStateOf(false) }

            DrawerMenuTestSurface {
                NavHost(
                    navController = navController,
                    startDestination = Routes.Home,
                ) {
                    composable<Routes.Home> {
                        Text("Home", modifier = Modifier.testTag("HomeRoute"))
                    }
                }
                DrawerMenu(
                    drawerState = drawerState,
                    rootNavController = navController,
                    hasSeenWidgetsIntro = true,
                    hasSeenShopIntro = true,
                    onBeforeNavigate = {},
                    showWidgets = true,
                    onOpenWidgetsHome = { openWidgetsHome.value = true },
                )
                if (openWidgetsHome.value) {
                    Text("Widgets requested", modifier = Modifier.testTag("WidgetsHomeRequested"))
                }
            }
        }

        composeTestRule.onNodeWithTag("DrawerWidgets").performClick()

        composeTestRule.onNodeWithTag("WidgetsHomeRequested").assertIsDisplayed()
    }

    @Test
    fun testSeenWidgetsIntroWithDisabledWidgetsOpensSheet() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            val drawerState = rememberDrawerState(DrawerValue.Open)
            val openWidgetsSheet = remember { mutableStateOf(false) }

            DrawerMenuTestSurface {
                NavHost(
                    navController = navController,
                    startDestination = Routes.Home,
                ) {
                    composable<Routes.Home> {
                        Text("Home", modifier = Modifier.testTag("HomeRoute"))
                    }
                }
                DrawerMenu(
                    drawerState = drawerState,
                    rootNavController = navController,
                    hasSeenWidgetsIntro = true,
                    hasSeenShopIntro = true,
                    onBeforeNavigate = {},
                    showWidgets = false,
                    onOpenWidgetsHome = { error("Should not request home widgets page") },
                    onOpenWidgetsSheet = { openWidgetsSheet.value = true },
                )
                if (openWidgetsSheet.value) {
                    Text("Widgets sheet requested", modifier = Modifier.testTag("WidgetsSheetRequested"))
                }
            }
        }

        composeTestRule.onNodeWithTag("DrawerWidgets").performClick()

        composeTestRule.onNodeWithTag("WidgetsSheetRequested").assertIsDisplayed()
    }
}

@Composable
private fun DrawerMenuTestSurface(content: @Composable () -> Unit) {
    AppThemeSurface {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            content()
        }
    }
}
