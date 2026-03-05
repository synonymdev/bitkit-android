package to.bitkit.models

import com.synonym.bitkitcore.AddressType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressTypeTest {

    @Test
    fun `toSettingsString maps all types correctly`() {
        assertEquals("taproot", AddressType.P2TR.toSettingsString())
        assertEquals("nativeSegwit", AddressType.P2WPKH.toSettingsString())
        assertEquals("nestedSegwit", AddressType.P2SH.toSettingsString())
        assertEquals("legacy", AddressType.P2PKH.toSettingsString())
    }

    @Test
    fun `toAddressType maps all strings correctly`() {
        assertEquals(AddressType.P2TR, "taproot".toAddressType())
        assertEquals(AddressType.P2WPKH, "nativeSegwit".toAddressType())
        assertEquals(AddressType.P2SH, "nestedSegwit".toAddressType())
        assertEquals(AddressType.P2PKH, "legacy".toAddressType())
    }

    @Test
    fun `toAddressType returns null for unknown strings`() {
        assertNull("unknown".toAddressType())
        assertNull("".toAddressType())
    }

    @Test
    fun `toSettingsString and toAddressType are inverses`() {
        val types = listOf(AddressType.P2TR, AddressType.P2WPKH, AddressType.P2SH, AddressType.P2PKH)
        types.forEach { type ->
            assertEquals(type, type.toSettingsString().toAddressType())
        }
    }

    @Test
    fun `addressTypeFromAddress detects taproot addresses`() {
        assertEquals("taproot", "bc1pabc123".addressTypeFromAddress())
        assertEquals("taproot", "tb1pdef456".addressTypeFromAddress())
        assertEquals("taproot", "bcrt1pabc123".addressTypeFromAddress())
    }

    @Test
    fun `addressTypeFromAddress detects native segwit addresses`() {
        assertEquals("nativeSegwit", "bc1qabc123".addressTypeFromAddress())
        assertEquals("nativeSegwit", "tb1qabc123".addressTypeFromAddress())
        assertEquals("nativeSegwit", "bcrt1qabc123".addressTypeFromAddress())
    }

    @Test
    fun `addressTypeFromAddress detects nested segwit addresses`() {
        assertEquals("nestedSegwit", "3abc123".addressTypeFromAddress())
        assertEquals("nestedSegwit", "2abc123".addressTypeFromAddress())
    }

    @Test
    fun `addressTypeFromAddress detects legacy addresses`() {
        assertEquals("legacy", "1abc123".addressTypeFromAddress())
        assertEquals("legacy", "mabc123".addressTypeFromAddress())
        assertEquals("legacy", "nabc123".addressTypeFromAddress())
    }

    @Test
    fun `addressTypeFromAddress returns null for unknown`() {
        assertNull("xabc123".addressTypeFromAddress())
        assertNull("".addressTypeFromAddress())
    }
}
