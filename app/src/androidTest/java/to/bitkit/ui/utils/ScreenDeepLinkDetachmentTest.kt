package to.bitkit.ui.utils

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.Routes
import kotlin.reflect.KClass
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@ComposeUi
class ScreenDeepLinkDetachmentTest {
    private companion object {
        const val SETTINGS_URI = "bitkit://screen/settings"
        const val DENIED_URI = "bitkit://screen/recovery-mnemonic"
    }

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var navController: TestNavHostController

    @Test
    fun testAttachedScreenUriIsHandledByGraphWithoutGate() {
        withGraph(detach = false) {
            assertTrue(isOn(Routes.Settings::class))
        }
    }

    @Test
    fun testGraphCreationStaysOnHomeAfterDetachment() {
        withGraph(detach = true) { activity ->
            assertNull(activity.intent.data)
            assertTrue(isOn(Routes.Home::class))
        }
    }

    @Test
    fun testDetachedUriReachesSettingsOnlyThroughReplay() {
        withGraph(detach = true) { activity ->
            assertTrue(isOn(Routes.Home::class))

            replay(activity, SETTINGS_URI)

            assertTrue(isOn(Routes.Settings::class))
        }
    }

    @Test
    fun testDeniedRouteIsNotMatchedByReplay() {
        withGraph(detach = true) { activity ->
            replay(activity, DENIED_URI)

            assertTrue(isOn(Routes.Home::class))
        }
    }

    private fun withGraph(detach: Boolean, block: (ComponentActivity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent = Intent(context, ComponentActivity::class.java)

        ActivityScenario.launch<ComponentActivity>(launchIntent).use { scenario ->
            lateinit var activity: ComponentActivity
            lateinit var launched: Intent

            scenario.onActivity {
                activity = it
                launched = it.intent

                val delivered = Intent(Intent.ACTION_VIEW, SETTINGS_URI.toUri())
                if (detach) {
                    ScreenDeepLinks.detachScreenUri(delivered)
                }
                it.intent = delivered
                it.setContent { TestGraph() }
            }
            composeTestRule.waitForIdle()

            block(activity)

            scenario.onActivity { it.intent = launched }
        }
    }

    private fun replay(activity: ComponentActivity, uri: String) {
        activity.runOnUiThread {
            navController.handleDeepLink(
                Intent(Intent.ACTION_VIEW, uri.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun isOn(route: KClass<out Routes>): Boolean =
        navController.currentDestination?.hasRoute(route) == true

    @Composable
    private fun TestGraph() {
        val context = LocalContext.current
        val controller = remember {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
        navController = controller

        NavHost(navController = controller, startDestination = Routes.Home) {
            composable<Routes.Home>(deepLinks = ScreenDeepLinks.linksFor(Routes.Home::class)) {
                Text("home")
            }
            composable<Routes.Settings>(deepLinks = ScreenDeepLinks.linksFor(Routes.Settings::class)) {
                Text("settings")
            }
        }
    }
}
