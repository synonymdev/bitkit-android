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
    fun `posts each lost leaf event, and a failure unless every leaf was lost`() {
        val lost = running.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 16_384uL)))
        val claimed = lost.copy(phase = Phase.Failed(SparkExitDriver.LOST_REASON))
        val stopped = running.copy(phase = Phase.Failed(SparkExitDriver.NO_LEAF_REASON))

        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(lost, running))
        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(claimed, running))
        assertEquals(emptyList(), SparkExitAlerts.losses(claimed, lost))
        assertEquals(listOf(SparkExitAlert.Failed), SparkExitAlerts.losses(stopped, running))
        // Every leaf lost says it alone, whatever the reason; a leaf left behind as well also fails the exit.
        val stoppedLost = stopped.copy(lostLeaves = lost.lostLeaves)
        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(stoppedLost, running))
        val leftBehind = SparkExitRecord.LeftBehindLeaf("leaf-2", 4_000uL, "Not recoverable at this fee rate")
        assertEquals(
            listOf(SparkExitAlert.Lost, SparkExitAlert.Failed),
            SparkExitAlerts.losses(
                stoppedLost.copy(leafIds = listOf("leaf-1", "leaf-2"), leftBehindLeaves = listOf(leftBehind)),
                running,
            ),
        )
        assertEquals(emptyList(), SparkExitAlerts.losses(stopped, stopped))
        assertEquals(emptyList(), SparkExitAlerts.losses(SparkExitFixtures.record(Phase.Done), running))
    }

    @Test
    fun `posts no second loss for a leaf an earlier exit already reported`() {
        val lost = running.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 65_536uL)))
        val claimed = lost.copy(phase = Phase.Failed(SparkExitDriver.LOST_REASON))

        assertEquals(emptyList(), SparkExitAlerts.losses(lost, running, reportedLost = setOf("leaf-1")))
        assertEquals(emptyList(), SparkExitAlerts.losses(claimed, running, reportedLost = setOf("leaf-1")))
        val another = lost.copy(lostLeaves = lost.lostLeaves + SparkExitRecord.LostLeaf("leaf-2", 4_000uL))
        assertEquals(
            listOf(SparkExitAlert.Lost),
            SparkExitAlerts.losses(another, running, reportedLost = setOf("leaf-1")),
        )
    }
}
