package to.bitkit.utils

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ProtocolException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityErrorsTest {
    @Test
    fun `returns true for ktor request timeout`() {
        val error = HttpRequestTimeoutException("https://feeds.synonym.to", 10_000, null)

        assertTrue(error.isConnectivityFailure())
    }

    @Test
    fun `returns true for unresolved address`() {
        assertTrue(UnresolvedAddressException().isConnectivityFailure())
    }

    @Test
    fun `returns true for nested connectivity failure`() {
        val error = IllegalStateException("Wrapped timeout", UnresolvedAddressException())

        assertTrue(error.isConnectivityFailure())
    }

    @Test
    fun `returns false for non connectivity failure`() {
        assertFalse(ProtocolException("Bad response").isConnectivityFailure())
    }
}
