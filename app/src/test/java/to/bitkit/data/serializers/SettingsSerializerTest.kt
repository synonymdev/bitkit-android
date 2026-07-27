package to.bitkit.data.serializers

import kotlinx.serialization.encodeToString
import org.junit.Test
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import java.io.ByteArrayInputStream
import kotlin.test.assertTrue

class SettingsSerializerTest : BaseUnitTest() {
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
