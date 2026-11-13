package to.bitkit.repositories

import org.junit.Test
import kotlin.test.assertEquals

class WakeConfigTest {
    @Test
    fun `an install opts in to wakes only with Background Payments on and the permission still granted`() {
        val cases = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        )

        cases.forEach { (backgroundPaymentsEnabled, hasNotificationPermission, expected) ->
            assertEquals(
                expected,
                isWakeOptedIn(backgroundPaymentsEnabled, hasNotificationPermission),
                "backgroundPayments=$backgroundPaymentsEnabled permission=$hasNotificationPermission",
            )
        }
    }
}
