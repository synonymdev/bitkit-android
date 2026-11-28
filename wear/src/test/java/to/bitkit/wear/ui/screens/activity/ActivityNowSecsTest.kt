package to.bitkit.wear.ui.screens.activity

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The time the activity list's relative times count from. */
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round")
@RunWith(RobolectricTestRunner::class)
class ActivityNowSecsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the times count from the clock before a list is read, from a new list at once, and move on by the minute`() {
        var clockSecs = NOW
        var loadedSecs by mutableLongStateOf(0L)
        var shown = -1L
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { shown = rememberNowSecs(loadedSecs = loadedSecs, clock = { clockSecs }) }

        // No list yet: never epoch 0, which would make every payment read "0 min. ago".
        composeRule.runOnIdle { assertEquals(NOW, shown) }

        // A list read between two ticks counts from its own time on its first frame.
        composeRule.runOnUiThread {
            clockSecs = NOW + 45
            loadedSecs = NOW + 45
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.runOnIdle { assertEquals(NOW + 45, shown) }

        clockSecs = NOW + 120
        composeRule.mainClock.advanceTimeBy(61_000)
        composeRule.runOnIdle { assertEquals(NOW + 120, shown) }
    }

    private companion object {
        const val NOW = 1_800_000_000L
    }
}
