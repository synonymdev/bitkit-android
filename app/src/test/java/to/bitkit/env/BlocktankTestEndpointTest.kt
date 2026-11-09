package to.bitkit.env

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlocktankTestEndpointTest {
    @Test
    fun `disabled fixture ignores configuration`() {
        assertNull(BlocktankTestEndpoint.resolve(false, "https://remote.example/api"))
    }

    @Test
    fun `empty configuration retains the ordinary endpoint`() {
        assertNull(BlocktankTestEndpoint.resolve(true, null))
        assertNull(BlocktankTestEndpoint.resolve(true, " "))
    }

    @Test
    fun `localhost fixture preserves explicit transport port and path`() {
        listOf("http", "https").forEach { scheme ->
            val endpoint = "$scheme://localhost:18685/api/v2"
            assertEquals(endpoint, BlocktankTestEndpoint.resolve(true, " $endpoint "))
        }
    }

    @Test
    fun `unsafe or incomplete fixture configuration fails closed`() {
        listOf(
            "https://remote.example:18685/api",
            "http://127.0.0.1:18685/api",
            "http://localhost/api",
            "http://localhost:18685",
            "http://localhost:0/api",
            "http://localhost:65536/api",
            "file://localhost:18685/api",
            "http://user@localhost:18685/api",
            "http://localhost:18685/api?token=x",
            "http://localhost:18685/api#fragment",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> { BlocktankTestEndpoint.resolve(true, endpoint) }
        }
    }
}
