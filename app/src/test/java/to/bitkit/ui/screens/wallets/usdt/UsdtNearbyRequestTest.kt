package to.bitkit.ui.screens.wallets.usdt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsdtNearbyRequestTest {
    private val arbitrumUri = "ethereum:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9@42161/transfer?address=0x1111"

    @Test
    fun `offers the arbitrum request when no deposit is shown`() {
        assertEquals(arbitrumUri, usdtNearbyRequest(depositUri = null, receiveUri = arbitrumUri))
    }

    @Test
    fun `does not offer a tron deposit address`() {
        assertNull(usdtNearbyRequest(depositUri = "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8", receiveUri = arbitrumUri))
    }

    @Test
    fun `does not offer a deposit on another evm chain`() {
        val baseDeposit = "ethereum:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913@8453/transfer?address=0x2222"
        assertNull(usdtNearbyRequest(depositUri = baseDeposit, receiveUri = arbitrumUri))
    }

    @Test
    fun `offers nothing before the wallet request loads`() {
        assertNull(usdtNearbyRequest(depositUri = null, receiveUri = ""))
    }
}
