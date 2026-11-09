package to.bitkit.viewmodels

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllowanceOffersAcceptedDescriptionTest {
    companion object {
        private const val SINGLE = "{name} set up an allowance for you"
        private const val SEVERAL = "{names} set up allowances for you"
        private val AND_LIST: (List<String>) -> String = { it.joinToString(" and ") }
    }

    @Test
    fun `offers accepted at once are announced in one description`() {
        assertEquals(
            "Alice and Bob set up allowances for you",
            allowanceOffersAcceptedDescription(listOf("Alice", "Bob"), SINGLE, SEVERAL, AND_LIST),
        )
    }

    @Test
    fun `one offer keeps the single description`() {
        assertEquals(
            "Alice set up an allowance for you",
            allowanceOffersAcceptedDescription(listOf("Alice"), SINGLE, SEVERAL, AND_LIST),
        )
    }

    @Test
    fun `no offer is not announced`() {
        assertNull(allowanceOffersAcceptedDescription(emptyList(), SINGLE, SEVERAL, AND_LIST))
    }
}
