package to.bitkit.ui.shared.effects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.LocalAppViewModel
import to.bitkit.ui.LocalIsAppLocked
import to.bitkit.viewmodels.AppViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class NearbyPauseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val connectivity = MutableStateFlow(ConnectivityState.CONNECTED)
    private val app = mock<AppViewModel> { on { isOnline }.thenReturn(connectivity) }

    private fun isPaused(isLocked: Boolean = false): Boolean {
        var paused: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppViewModel provides app, LocalIsAppLocked provides isLocked) {
                paused = isNearbyPaused()
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(paused)
    }

    @Test
    fun `runs while online and unlocked`() {
        assertEquals(false, isPaused())
    }

    @Test
    fun `pauses while the connection issues screen is shown`() {
        connectivity.value = ConnectivityState.DISCONNECTED
        assertEquals(true, isPaused())
    }

    @Test
    fun `pauses while the app is locked`() {
        assertEquals(true, isPaused(isLocked = true))
    }

    @Test
    fun `offers nothing while paused`() {
        assertNull(nearbyOfferUri("lnbcrt1p", isPaused = true))
        assertEquals("lightning:lnbcrt1p", nearbyOfferUri("lnbcrt1p", isPaused = false))
    }
}
