package to.bitkit.wear.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import to.bitkit.wear.env.Env
import to.bitkit.wear.env.WalletNetwork
import to.bitkit.wear.repositories.SettingsSyncRepo
import kotlin.test.assertEquals

/**
 * `phone-backup-contract.json` holds what the watch needs the phone to keep writing: the backup's key, the six
 * settings in the phone's own encoding, and where each network's backups live. The phone's `WatchBackupContractTest`
 * compares the same file with what the phone app really writes and uses, so a renamed field or a moved server fails
 * a test instead of silently turning QuickPay off on the watch.
 */
class PhoneBackupContractTest {
    private val contract: JsonObject by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResource("phone-backup-contract.json")).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `the watch reads the settings backup under the phone's key`() {
        assertEquals(contract.getValue("settingsKey").jsonPrimitive.content, SettingsSyncRepo.SETTINGS_KEY)
    }

    @Test
    fun `the watch reads all six settings as the phone writes them`() {
        val backup = contract.getValue("settingsBackup").toString().toByteArray()

        val expected = PhoneSettings(
            PrimaryDisplay.FIAT,
            BitcoinDisplayUnit.CLASSIC,
            "EUR",
            QuickPayLimits(isEnabled = true, amountUsd = 10, dailyMultiplier = 3),
        )
        assertEquals(expected, PhoneSettingsParser.parse(backup))
    }

    @Test
    fun `the watch looks for each network's backups where the phone stores them`() {
        val networks = contract.getValue("networks").jsonObject

        assertEquals(WalletNetwork.entries.map { it.name }.toSet(), networks.keys)
        for (network in WalletNetwork.entries) {
            val expected = networks.getValue(network.name).jsonObject.mapValues { it.value.jsonPrimitive.content }
            val actual = mapOf(
                "storeIdPrefix" to Env.vssStoreIdPrefix(network),
                "vssServerUrl" to Env.vssServerUrl(network),
                "lnurlAuthServerUrl" to Env.lnurlAuthServerUrl(network),
            )
            assertEquals(expected, actual, "$network")
        }
    }
}
