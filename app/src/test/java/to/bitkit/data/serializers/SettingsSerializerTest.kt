package to.bitkit.data.serializers

import kotlinx.serialization.encodeToString
import org.junit.Test
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSerializerTest : BaseUnitTest() {
    @Test
    fun `legacy existing settings do not acquire a build default primary`() = test {
        val result = SettingsSerializer.readFrom(ByteArrayInputStream("{}".encodeToByteArray()))
        assertEquals("", result.primaryNodeUri)
    }

    @Test
    fun `explicit saved blank and Iroh primary settings remain unchanged`() = test {
        val primary = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" +
            "@iroh:${"ab".repeat(32)}?relay=https%3A%2F%2Frelay.example.%2F"
        for (uri in listOf("", primary)) {
            val settings = SettingsData(primaryNodeUri = uri)
            val result = SettingsSerializer.readFrom(
                ByteArrayInputStream(json.encodeToString(settings).encodeToByteArray())
            )
            assertEquals(uri, result.primaryNodeUri)
        }
    }

    @Test
    fun `read resets hidden Paykit payment methods`() = test {
        val stored = SettingsData(
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = false,
        )

        val result = SettingsSerializer.readFrom(
            ByteArrayInputStream(json.encodeToString(stored).encodeToByteArray())
        )

        assertTrue(result.publicPaykitLightningEnabled)
        assertTrue(result.publicPaykitOnchainEnabled)
    }
}
