package to.bitkit.data.serializers

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs

class WatchOnlyAccountDataSerializerTest {
    @Test
    fun `malformed JSON is reported as datastore corruption`() = runTest {
        val error = runCatching {
            WatchOnlyAccountDataSerializer.readFrom("{not-json".byteInputStream())
        }.exceptionOrNull()

        assertIs<CorruptionException>(error)
    }
}
