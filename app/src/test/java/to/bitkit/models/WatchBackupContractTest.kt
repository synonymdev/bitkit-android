package to.bitkit.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.env.Env
import java.io.File
import kotlin.test.assertEquals

/**
 * The Wear OS app imports the phone's settings by reading the settings backup from VSS (docs/wear.md, Settings
 * sync). `wear/src/test/resources/phone-backup-contract.json` holds what it relies on: the backup's key, six
 * settings as this app encodes them, and where each network's backups live. The watch's `PhoneBackupContractTest`
 * reads the same file. When a test here fails, the watch stops reading the phone's settings: change the watch's
 * reader and the file together with the phone.
 */
class WatchBackupContractTest {
    private val contract: JsonObject by lazy {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "wear/src/test/resources/phone-backup-contract.json") }
            .first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `the settings backup is stored under the key the watch reads`() {
        assertEquals(contract.getValue("settingsKey").jsonPrimitive.content, BackupCategory.SETTINGS.name)
    }

    @Test
    fun `the settings backup holds the six settings the watch reads, in the form it reads`() {
        val expected = contract.getValue("settingsBackup").jsonObject
        val expectedSettings = expected.getValue("settings").jsonObject
        val settings = SettingsData(
            primaryDisplay = PrimaryDisplay.FIAT,
            displayUnit = BitcoinDisplayUnit.CLASSIC,
            selectedCurrency = "EUR",
            isQuickPayEnabled = true,
            quickPayAmount = 10,
            quickPayDailyLimitMultiplier = 3,
        )

        val written = written(settings)

        assertEquals(expected.getValue("version"), written.getValue("version"))
        val writtenSettings = written.getValue("settings").jsonObject
        assertEquals(expectedSettings, JsonObject(writtenSettings.filterKeys { it in expectedSettings.keys }))
    }

    @Test
    fun `default settings are written too, so the watch always finds all six`() {
        val keys = contract.getValue("settingsBackup").jsonObject.getValue("settings").jsonObject.keys

        val written = written(SettingsData()).getValue("settings").jsonObject

        assertEquals(keys, written.keys.intersect(keys))
    }

    @Test
    fun `this network's backups live where the watch looks for them`() {
        // An E2E build may point the backups at a local server, which the watch never reads.
        assumeFalse(Env.isE2eTest)
        val network = contract.getValue("networks").jsonObject[Env.network.name]?.jsonObject
        // The watch runs on fewer networks than the phone.
        assumeTrue(network != null)

        val expected = checkNotNull(network).mapValues { it.value.jsonPrimitive.content }
        val actual = mapOf(
            "storeIdPrefix" to Env.vssStoreIdPrefix,
            "vssServerUrl" to Env.vssServerUrl,
            "lnurlAuthServerUrl" to Env.lnurlAuthServerUrl,
        )
        assertEquals(expected, actual)
    }

    /** The settings backup of [settings] as this app uploads it. */
    private fun written(settings: SettingsData): JsonObject {
        val backup = SettingsBackupV1(createdAt = 0, settings = settings)
        return json.parseToJsonElement(json.encodeToString(SettingsBackupV1.serializer(), backup)).jsonObject
    }
}
