package to.bitkit.repositories

import org.junit.Test
import to.bitkit.repositories.SparkExitRecord.Phase
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SparkExitAlertsTest {
    private val running = SparkExitFixtures.record(Phase.Running)
    private val unlimited = SparkExitBackgroundLimits()

    @Test
    fun `names why a running exit fell behind, the most specific first`() {
        assertEquals(SparkExitAlert.Offline, SparkExitAlerts.reason(running, isOnline = false, unlimited))
        assertEquals(
            SparkExitAlert.Restricted,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isRestricted = true, isOptimized = true)),
        )
        assertEquals(
            SparkExitAlert.Battery,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isPowerSaveMode = true)),
        )
        assertEquals(
            SparkExitAlert.Battery,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isOptimized = true)),
        )
        assertEquals(SparkExitAlert.Behind, SparkExitAlerts.reason(running, isOnline = true, unlimited))
    }

    @Test
    fun `asks for fee funding and stays quiet while nothing needs the user`() {
        assertEquals(
            SparkExitAlert.Funding,
            SparkExitAlerts.reason(SparkExitFixtures.record(Phase.NeedsFunding(5_000uL)), isOnline = false, unlimited),
        )
        assertNull(SparkExitAlerts.reason(SparkExitFixtures.record(), isOnline = false, unlimited))
        assertNull(SparkExitAlerts.reason(SparkExitFixtures.record(Phase.Done), isOnline = false, unlimited))
        assertNull(SparkExitAlerts.reason(null, isOnline = false, unlimited))
    }
}
