package to.bitkit.nfc

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcTagEmitterTest {
    private val tokens = mutableListOf<Long>()

    @After
    fun tearDown() {
        tokens.forEach { NfcTagEmitter.clear(it) }
    }

    @Test
    fun `serves the published request until its owner clears it`() {
        val token = NfcTagEmitter.publish("lightning:lnbc1").also(tokens::add)

        assertArrayEquals(NdefCodec.encodeUriMessage("lightning:lnbc1"), NfcTagEmitter.currentMessage())
        assertTrue(NfcTagEmitter.clear(token))
        assertNull(NfcTagEmitter.currentMessage())
    }

    @Test
    fun `an older publisher cannot clear a newer request`() {
        val first = NfcTagEmitter.publish("lightning:lnbc1").also(tokens::add)
        NfcTagEmitter.publish("lightning:lnbc2").also(tokens::add)

        assertFalse(NfcTagEmitter.clear(first))
        assertArrayEquals(NdefCodec.encodeUriMessage("lightning:lnbc2"), NfcTagEmitter.currentMessage())
    }
}
