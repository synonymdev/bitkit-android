package to.bitkit.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class NfcReadDispatcherTest {
    private val reads = mutableListOf<String>()
    private var unreadable = 0
    private var time = 0L
    private val sut = NfcReadDispatcher(onRead = { reads += it }, onUnreadable = { unreadable++ }, now = { time })

    @Test
    fun `delivers a read and reports an unreadable tag`() {
        sut.deliver("lightning:lnbcrt1")
        sut.deliver(null)

        assertEquals(listOf("lightning:lnbcrt1"), reads)
        assertEquals(1, unreadable)
    }

    @Test
    fun `drops a repeat of the same request within the window`() {
        sut.deliver("lightning:lnbcrt1")
        time += 1_000
        sut.deliver("lightning:lnbcrt1")
        sut.deliver("lightning:lnbcrt2")
        time += NfcReadDispatcher.DUPLICATE_READ_WINDOW.inWholeMilliseconds
        sut.deliver("lightning:lnbcrt2")

        assertEquals(listOf("lightning:lnbcrt1", "lightning:lnbcrt2", "lightning:lnbcrt2"), reads)
    }

    @Test
    fun `drops a read that lands after the screen closed`() {
        sut.close()
        sut.deliver("lightning:lnbcrt1")
        sut.deliver(null)

        assertEquals(emptyList<String>(), reads)
        assertEquals(0, unreadable)
    }
}
