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
        val refused = running.copy(
            broadcastLog = listOf(
                SparkExitRecord.BroadcastAttempt(
                    generation = running.generation,
                    txid = "n1",
                    attempts = 3,
                    rejections = 3,
                    lastAttemptAt = 0L,
                    lastOutcome = "rejected: min relay fee not met",
                    isAccepted = false,
                ),
            ),
        )
        assertEquals(SparkExitAlert.Rejected, SparkExitAlerts.reason(refused, isOnline = false, unlimited))
        assertEquals(SparkExitAlert.Offline, SparkExitAlerts.reason(running, isOnline = false, unlimited))
        assertEquals(
            SparkExitAlert.Restricted,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isRestricted = true, isOptimized = true)),
        )
        assertEquals(
            SparkExitAlert.Battery,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isPowerSaveMode = true)),
        )
        // Battery optimization is Android's default, so alone it is no cause to name.
        assertEquals(
            SparkExitAlert.Behind,
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

    @Test
    fun `posts a lost leaf or a failed exit once, the lost leaves when both happen together`() {
        val lost = running.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 16_384uL)))
        val failed = lost.copy(phase = Phase.Failed(SparkExitDriver.LOST_REASON))

        assertEquals(SparkExitAlert.Lost, SparkExitAlerts.loss(lost, running))
        assertEquals(SparkExitAlert.Lost, SparkExitAlerts.loss(failed, running))
        assertEquals(SparkExitAlert.Failed, SparkExitAlerts.loss(failed, lost))
        assertNull(SparkExitAlerts.loss(failed, failed))
        assertNull(SparkExitAlerts.loss(SparkExitFixtures.record(Phase.Done), running))
    }
}
