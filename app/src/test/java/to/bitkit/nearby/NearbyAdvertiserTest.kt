package to.bitkit.nearby

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Looper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class NearbyAdvertiserTest {
    private val advertiser = mock<BluetoothLeAdvertiser>()
    private val adapter = mock<BluetoothAdapter> { on { bluetoothLeAdvertiser }.thenReturn(advertiser) }
    private val server = mock<BluetoothGattServer>()
    private val manager = mock<BluetoothManager> { on { adapter }.thenReturn(adapter) }
    private val context = mock<Context> { on { getSystemService(Context.BLUETOOTH_SERVICE) }.thenReturn(manager) }
    private var failures = 0
    private lateinit var sut: NearbyAdvertiser

    @Before
    fun setUp() {
        whenever(manager.openGattServer(any(), any())).thenReturn(server)
        whenever(server.addService(any())).thenReturn(true)
        sut = NearbyAdvertiser(context) { failures++ }
    }

    private fun serverCallback(): BluetoothGattServerCallback {
        val captor = argumentCaptor<BluetoothGattServerCallback>()
        verify(manager).openGattServer(any(), captor.capture())
        return captor.lastValue
    }

    private fun reportServiceAdded(status: Int) {
        serverCallback().onServiceAdded(status, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `advertises only after the service is registered`() {
        sut.start(URI)
        verify(advertiser, never()).startAdvertising(any(), any(), any())

        reportServiceAdded(BluetoothGatt.GATT_SUCCESS)

        verify(advertiser).startAdvertising(any(), any(), any())
        assertEquals(0, failures)
    }

    @Test
    fun `does not advertise when the service is refused`() {
        whenever(server.addService(any())).thenReturn(false)

        sut.start(URI)

        verify(advertiser, never()).startAdvertising(any(), any(), any())
        verify(server).close()
        assertEquals(1, failures)
    }

    @Test
    fun `does not advertise when the service registration fails`() {
        sut.start(URI)

        reportServiceAdded(BluetoothGatt.GATT_FAILURE)

        verify(advertiser, never()).startAdvertising(any(), any(), any())
        verify(server).close()
        assertEquals(1, failures)
    }

    private companion object {
        const val URI = "bitcoin:bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
    }
}
