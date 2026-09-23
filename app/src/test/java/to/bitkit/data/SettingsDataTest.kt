package to.bitkit.data

import org.junit.Test
import kotlin.test.assertEquals

class SettingsDataTest {
    @Test
    fun `native SegWit monitoring is added to Taproot-only settings`() {
        val updated = SettingsData(
            selectedAddressType = "taproot",
            addressTypesToMonitor = listOf("taproot"),
        ).withRequiredNativeSegwitMonitoring()

        assertEquals(listOf("taproot", "nativeSegwit"), updated.addressTypesToMonitor)
    }
}
