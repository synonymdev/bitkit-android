package to.bitkit.data

import org.junit.Test
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaykitSharingMigrationTest : BaseUnitTest() {
    @Test
    fun `legacy combined sharing is not public consent and keeps private intent`() = test {
        for (privateEnabled in listOf(false, true)) {
            val legacy = json.decodeFromString<SettingsData>(
                """{"hasConfirmedPublicPaykitEndpoints":true,"sharesPublicPaykitEndpoints":true,
                    "sharesPrivatePaykitEndpoints":$privateEnabled}""",
            )
            assertTrue(PaykitSharingMigration.shouldMigrate(legacy))
            val migrated = PaykitSharingMigration.migrate(legacy)
            assertFalse(migrated.sharesPublicPaykitEndpoints)
            assertFalse(migrated.hasConfirmedPublicPaykitEndpoints)
            assertEquals(privateEnabled, migrated.sharesPrivatePaykitEndpoints)
            assertTrue(migrated.publicPaykitCleanupPending)
            assertTrue(migrated.legacyPublicAddressQuarantinePending)
            assertFalse(PaykitSharingMigration.shouldMigrate(migrated))
            assertEquals(migrated, migrated.withIndependentPaykitSharing())
        }
    }

    @Test
    fun `fresh preferences do not queue cleanup and explicit consent survives restart`() = test {
        for (privateEnabled in listOf(false, true)) {
            val privateOnly = PaykitSharingMigration.migrate(
                SettingsData(sharesPrivatePaykitEndpoints = privateEnabled)
            )
            assertEquals(privateEnabled, privateOnly.sharesPrivatePaykitEndpoints)
            assertFalse(privateOnly.hasPublicPaykitPublicationState())
            assertFalse(privateOnly.legacyPublicAddressQuarantinePending)
        }
        val fresh = PaykitSharingMigration.migrate(SettingsData())
        assertFalse(fresh.sharesPrivatePaykitEndpoints)
        assertFalse(fresh.sharesPublicPaykitEndpoints)
        assertFalse(fresh.publicPaykitCleanupPending)
        val optedIn = fresh.copy(hasConfirmedPublicPaykitEndpoints = true, sharesPublicPaykitEndpoints = true)
        val restarted = json.decodeFromString<SettingsData>(json.encodeToString(optedIn))
        assertFalse(PaykitSharingMigration.shouldMigrate(restarted))
        assertEquals(optedIn, restarted.withIndependentPaykitSharing())
    }
}
