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
        val activation = sut.open()
        sut.deliver("lightning:lnbcrt1", activation)
        sut.deliver(null, activation)

        assertEquals(listOf("lightning:lnbcrt1"), reads)
        assertEquals(1, unreadable)
    }

    @Test
    fun `drops a repeat of the same request within the window`() {
        val activation = sut.open()
        sut.deliver("lightning:lnbcrt1", activation)
        time += 1_000
        sut.deliver("lightning:lnbcrt1", activation)
        sut.deliver("lightning:lnbcrt2", activation)
        time += NfcReadDispatcher.DUPLICATE_READ_WINDOW.inWholeMilliseconds
        sut.deliver("lightning:lnbcrt2", activation)

        assertEquals(listOf("lightning:lnbcrt1", "lightning:lnbcrt2", "lightning:lnbcrt2"), reads)
    }

    @Test
    fun `drops a read from a reader that paused or closed`() {
        val activation = sut.open()
        sut.close()
        sut.deliver("lightning:lnbcrt1", activation)
        sut.deliver(null, activation)

        assertEquals(emptyList<String>(), reads)
        assertEquals(0, unreadable)
    }

    @Test
    fun `drops a read from an earlier activation after the reader resumed`() {
        val paused = sut.open()
        sut.close()
        val resumed = sut.open()
        sut.deliver("lightning:lnbcrt1", paused)
        sut.deliver("lightning:lnbcrt2", resumed)

        assertEquals(listOf("lightning:lnbcrt2"), reads)
    }

    @Test
    fun `drops a read before the reader opens`() {
        sut.deliver("lightning:lnbcrt1", 0)

        assertEquals(emptyList<String>(), reads)
    }
}
