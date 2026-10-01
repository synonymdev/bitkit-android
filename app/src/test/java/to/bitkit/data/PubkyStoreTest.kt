package to.bitkit.data

import kotlinx.coroutines.test.runTest
import org.junit.Test
import to.bitkit.data.serializers.PubkyStoreSerializer
import to.bitkit.test.forEachCase
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PubkyStoreTest {
    @Test
    fun `cachedProfile exposes the cached name and image only with their owner`() {
        val withOwner = PubkyStoreData(
            ownerPublicKey = "pubkybob",
            cachedProfileOwner = "pubkyalice",
            cachedName = "Alice",
            cachedImageUri = "pubky://avatar",
        )
        listOf(
            Triple("with owner", withOwner, PubkyCachedProfile("pubkyalice", "Alice", "pubky://avatar")),
            Triple("without owner", PubkyStoreData(ownerPublicKey = "pubkyalice", cachedName = "Alice"), null),
        ).forEachCase({ it.first }) { (case, data, expected) -> assertEquals(expected, data.cachedProfile(), case) }
    }

    @Test
    fun `stored data without a cached profile owner exposes no cached profile`() = runTest {
        val stored = """{"ownerPublicKey":"pubkyalice","cachedName":"Alice","cachedImageUri":"pubky://avatar"}"""

        val data = PubkyStoreSerializer.readFrom(stored.byteInputStream())

        assertEquals("Alice", data.cachedName)
        assertNull(data.cachedProfile())
    }
}
