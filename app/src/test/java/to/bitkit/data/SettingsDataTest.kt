package to.bitkit.data

import org.junit.Test
import to.bitkit.env.Env
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

    @Test
    fun `retired default Electrum servers are replaced with the current default`() {
        listOf(
            "ssl://fulcrum.bitkit.blocktank.to:8900",
            "ssl://35.187.18.233:8900",
            " SSL://Fulcrum.Bitkit.Blocktank.to:8900 ",
        ).forEach { retired ->
            val updated = SettingsData(electrumServer = retired).withRetiredElectrumServerReplaced()

            assertEquals(Env.electrumServerUrl, updated.electrumServer, retired)
        }
    }

    @Test
    fun `custom Electrum servers are kept`() {
        listOf(
            "ssl://electrum.example.com:50002",
            "tcp://192.168.1.10:60001",
            "ssl://fulcrum.bitkit.blocktank.to:50002",
        ).forEach { custom ->
            val updated = SettingsData(electrumServer = custom).withRetiredElectrumServerReplaced()

            assertEquals(custom, updated.electrumServer)
        }
    }
}
