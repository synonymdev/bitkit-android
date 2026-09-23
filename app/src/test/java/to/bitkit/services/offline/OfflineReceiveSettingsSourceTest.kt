package to.bitkit.services.offline

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import to.bitkit.env.Env
import to.bitkit.models.OfflineReceiveDevSettings
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineReceiveSettingsSourceTest : BaseUnitTest() {
    private val devSettings = MutableStateFlow(OfflineReceiveDevSettings())

    @Test
    fun `defaults are disabled and point at the trusted LSP peer`() = test {
        val settings = OfflineReceiveSettingsSource(devSettings, isNativeAvailable = true).current()

        assertFalse(settings.isEnabled)
        assertFalse(settings.isConfigured)
        assertEquals(Env.trustedLnPeers.first().nodeId, settings.settlementNodeId)
        assertTrue(settings.witnessNodeIds.isEmpty())
        assertEquals(OfflineReceiveSettings.DEFAULT_PREPARE_TIMEOUT_MILLIS, settings.prepareTimeoutMillis)
    }

    @Test
    fun `toggle enables only when the native provider was compiled in`() = test {
        devSettings.value = OfflineReceiveDevSettings(isEnabled = true)

        assertFalse(OfflineReceiveSettingsSource(devSettings, isNativeAvailable = false).current().isEnabled)
        val enabled = OfflineReceiveSettingsSource(devSettings, isNativeAvailable = true).current()
        assertTrue(enabled.isEnabled)
        assertTrue(enabled.isConfigured)
    }

    @Test
    fun `overrides replace the settlement peer and filter witnesses and timeout`() = test {
        devSettings.value = OfflineReceiveDevSettings(
            isEnabled = true,
            settlementNodeId = "02settlement",
            witnessNodeIds = listOf("02witness", "", " "),
            prepareTimeoutMillis = 15_000L,
        )

        val settings = OfflineReceiveSettingsSource(devSettings, isNativeAvailable = true).current()

        assertEquals("02settlement", settings.settlementNodeId)
        assertEquals(listOf("02witness"), settings.witnessNodeIds)
        assertEquals(15_000L, settings.prepareTimeoutMillis)

        devSettings.value = devSettings.value.copy(settlementNodeId = " ", prepareTimeoutMillis = 0L)
        val fallback = OfflineReceiveSettingsSource(devSettings, isNativeAvailable = true).current()
        assertEquals(Env.trustedLnPeers.first().nodeId, fallback.settlementNodeId)
        assertEquals(OfflineReceiveSettings.DEFAULT_PREPARE_TIMEOUT_MILLIS, fallback.prepareTimeoutMillis)
    }
}
