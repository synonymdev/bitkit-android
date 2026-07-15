package to.bitkit.data.serializers

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchOnlyAccountDataSerializerTest {
    @Test
    fun `existing data defaults pending removals to empty`() = runTest {
        val existingData = """
            {
              "accounts": [],
              "highestAccountIndexByWallet": {},
              "pendingAccountIndexByRequest": {}
            }
        """.trimIndent()

        val decoded = WatchOnlyAccountDataSerializer.readFrom(existingData.byteInputStream())

        assertTrue(decoded.accountsPendingRemoval.isEmpty())
    }

    @Test
    fun `malformed JSON is reported as datastore corruption`() = runTest {
        val error = runCatching {
            WatchOnlyAccountDataSerializer.readFrom("{not-json".byteInputStream())
        }.exceptionOrNull()

        assertIs<CorruptionException>(error)
    }
}
