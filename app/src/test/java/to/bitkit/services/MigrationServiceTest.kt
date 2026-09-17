package to.bitkit.services

import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationServiceTest : BaseUnitTest() {

    @Test
    fun `fetchOrdersInChunks should split ids into chunks and concatenate results`() = runTest {
        val orderIds = (1..25).map { "order$it" }
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(orderIds) { ids ->
            requests += ids
            ids.map { orderId -> mock<IBtOrder> { on { id } doReturn orderId } }
        }

        assertEquals(listOf(20, 5), requests.map { it.size })
        assertEquals(orderIds, requests.flatten())
        assertEquals(orderIds, result.map { it.id })
    }

    @Test
    fun `fetchOrdersInChunks should not fetch when ids are empty`() = runTest {
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(emptyList()) { ids ->
            requests += ids
            emptyList()
        }

        assertTrue(requests.isEmpty())
        assertTrue(result.isEmpty())
    }
}
