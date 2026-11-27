package to.bitkit.services

import com.synonym.bitkitcore.JadeTransportKind
import org.junit.After
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JadeEmulatorTransportTest {
    private val server = ServerSocket(0)
    private val address = "127.0.0.1:${server.localPort}"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `scan lists nothing when disabled`() {
        val sut = JadeEmulatorTransport(address = address, enabled = false)

        assertTrue(sut.scanDevices().isEmpty())
        assertFalse(sut.isEmulatorPath("tcp:$address"))
    }

    @Test
    fun `scan lists nothing when the emulator port is closed`() {
        server.close()
        val sut = JadeEmulatorTransport(address = address, enabled = true)

        assertTrue(sut.scanDevices().isEmpty())
    }

    @Test
    fun `scan lists the emulator as a serial device`() {
        thread { server.accept().close() }
        val sut = JadeEmulatorTransport(address = address, enabled = true)

        val devices = sut.scanDevices()

        assertEquals(1, devices.size)
        assertEquals("tcp:$address", devices.single().path)
        assertEquals(JadeTransportKind.SERIAL, devices.single().transport)
    }

    @Test
    fun `written bytes reach the emulator and its reply is read back`() {
        val path = "tcp:$address"
        thread {
            server.accept().use { client ->
                val request = ByteArray(3)
                client.getInputStream().read(request)
                client.getOutputStream().write(request.reversedArray())
            }
        }
        val sut = JadeEmulatorTransport(address = address, enabled = true)

        assertTrue(sut.openDevice(path).success)
        assertTrue(sut.writeChunk(path, byteArrayOf(1, 2, 3)).success)
        val read = sut.readChunk(path, 1_000u)

        assertTrue(read.success)
        assertContentEquals(byteArrayOf(3, 2, 1), read.data)
        assertTrue(sut.closeDevice(path).success)
    }

    @Test
    fun `read before open fails`() {
        val sut = JadeEmulatorTransport(address = address, enabled = true)

        assertFalse(sut.readChunk("tcp:$address", 10u).success)
    }
}
