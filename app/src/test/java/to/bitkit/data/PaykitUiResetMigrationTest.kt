package to.bitkit.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaykitUiResetMigrationTest {
    private val sut = PaykitUiResetMigration

    @Test
    fun `fresh install shows paykit and records the reset`() = runTest {
        val migrated = migrate(emptyPreferences())

        assertTrue(isPaykitEnabled(migrated))
        assertEquals(true, migrated[PaykitUiResetMigration.MIGRATED_KEY])
    }

    @Test
    fun `upgrade with paykit off shows paykit`() = runTest {
        val migrated = migrate(mutablePreferencesOf(SettingsStore.PAYKIT_ENABLED_KEY to false))

        assertNull(migrated[SettingsStore.PAYKIT_ENABLED_KEY])
        assertTrue(isPaykitEnabled(migrated))
    }

    @Test
    fun `upgrade with paykit on keeps paykit on`() = runTest {
        val migrated = migrate(mutablePreferencesOf(SettingsStore.PAYKIT_ENABLED_KEY to true))

        assertTrue(isPaykitEnabled(migrated))
    }

    @Test
    fun `upgrade keeps other local settings`() = runTest {
        val otherKey = booleanPreferencesKey("pubky_profile_setup_pending")
        val migrated = migrate(mutablePreferencesOf(SettingsStore.PAYKIT_ENABLED_KEY to false, otherKey to true))

        assertEquals(true, migrated[otherKey])
    }

    @Test
    fun `dev settings off after the reset stays off`() = runTest {
        val current = mutablePreferencesOf(
            SettingsStore.PAYKIT_ENABLED_KEY to false,
            PaykitUiResetMigration.MIGRATED_KEY to true,
        )

        assertFalse(sut.shouldMigrate(current))
        assertFalse(isPaykitEnabled(current))
    }

    private suspend fun migrate(current: Preferences): Preferences {
        assertTrue(sut.shouldMigrate(current))
        return sut.migrate(current)
    }

    private fun isPaykitEnabled(preferences: Preferences) = preferences[SettingsStore.PAYKIT_ENABLED_KEY] ?: true
}
