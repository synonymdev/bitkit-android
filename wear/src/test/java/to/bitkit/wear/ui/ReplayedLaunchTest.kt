package to.bitkit.wear.ui

import android.content.Intent
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayedLaunchTest {
    @Test
    fun `a link opened again from recents is a replay, whatever else its flags say`() {
        assertTrue(isReplayedLaunch(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(isReplayedLaunch(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun `a link that arrives now is not a replay`() {
        assertFalse(isReplayedLaunch(0))
        assertFalse(isReplayedLaunch(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
