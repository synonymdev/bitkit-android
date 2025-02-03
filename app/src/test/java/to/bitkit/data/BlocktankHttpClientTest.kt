package to.bitkit.data

import kotlinx.coroutines.test.runTest
import to.bitkit.di.HttpModule
import to.bitkit.di.json
import to.bitkit.models.blocktank.BitcoinNetworkEnum
import to.bitkit.models.blocktank.CreateOrderOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BlocktankHttpClientTest {
    private val sut = BlocktankHttpClient(HttpModule.provideHttpClient(json))

    @Test
    fun `test getInfo`() = runTest {
        val result = sut.getInfo()
        assertEquals(result.onchain.network, BitcoinNetworkEnum.regtest)
    }

    @Test
    fun `test orders`() = runTest {
        val order1 = sut.createOrder(
            lspBalanceSat = 100000,
            channelExpiryWeeks = 2,
            options = CreateOrderOptions.initWithDefaults().copy(clientBalanceSat = 1000)
        )
        val orderCheck = sut.getOrder(order1.id)
        assertEquals(order1, orderCheck)

        val order2 = sut.createOrder(
            lspBalanceSat = 123400,
            channelExpiryWeeks = 3,
            options = CreateOrderOptions.initWithDefaults().copy(clientBalanceSat = 1234)
        )
        val orders = sut.getOrders(listOf(order1.id, order2.id))
        assertContains(orders, order1)
        assertContains(orders, order2)
    }
}
