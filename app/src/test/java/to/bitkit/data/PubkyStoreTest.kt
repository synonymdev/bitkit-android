package to.bitkit.data

import kotlinx.coroutines.test.runTest
import org.junit.Test
import to.bitkit.data.serializers.PubkyStoreSerializer
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PubkyStoreTest {
    @Test
    fun `cachedProfile exposes the cached name and image with their owner`() {
        val data = PubkyStoreData(
            ownerPublicKey = "pubkybob",
            cachedProfileOwner = "pubkyalice",
            cachedName = "Alice",
            cachedImageUri = "pubky://avatar",
        )

        assertEquals(PubkyCachedProfile("pubkyalice", "Alice", "pubky://avatar"), data.cachedProfile())
    }

    @Test
    fun `cachedProfile is null without a cached profile owner`() {
        val data = PubkyStoreData(ownerPublicKey = "pubkyalice", cachedName = "Alice")

        assertNull(data.cachedProfile())
    }

    @Test
    fun `stored data without a cached profile owner exposes no cached profile`() = runTest {
        val stored = """{"ownerPublicKey":"pubkyalice","cachedName":"Alice","cachedImageUri":"pubky://avatar"}"""

        val data = PubkyStoreSerializer.readFrom(stored.byteInputStream())

        assertEquals("Alice", data.cachedName)
        assertNull(data.cachedProfile())
    }
}
