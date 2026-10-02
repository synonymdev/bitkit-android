package to.bitkit.utils

import android.content.Context
import com.synonym.bitkitcore.JadeException
import com.synonym.bitkitcore.TrezorException
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import kotlin.test.assertEquals

class HwErrorPresenterTest {

    private val context = mock<Context>()

    @Before
    fun setUp() {
        whenever(context.getString(R.string.hardware__jade_invalid_pin)).thenReturn("wrong pin")
        whenever(context.getString(R.string.hardware__jade_uninitialized)).thenReturn("not set up")
        whenever(context.getString(R.string.hardware__jade_firmware_outdated)).thenReturn("old firmware")
        whenever(context.getString(R.string.hardware__jade_psbt_too_large)).thenReturn("too large")
        whenever(context.getString(R.string.hardware__jade_network_mismatch)).thenReturn("wrong network")
        whenever(context.getString(R.string.hardware__jade_device_busy)).thenReturn("jade busy")
        whenever(context.getString(R.string.hardware__jade_pinserver_error)).thenReturn("pinserver")
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn("trezor busy")
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("connect error")
    }

    @Test
    fun `maps typed jade errors to their messages`() {
        assertEquals("wrong pin", HwErrorPresenter.userMessage(context, JadeException.InvalidPin()))
        assertEquals("not set up", HwErrorPresenter.userMessage(context, JadeException.DeviceUninitialized()))
        assertEquals("old firmware", HwErrorPresenter.userMessage(context, JadeException.UnsupportedFirmware("a", "b")))
        assertEquals("too large", HwErrorPresenter.userMessage(context, JadeException.PsbtTooLarge(20_000uL, 16_384uL)))
        assertEquals("wrong network", HwErrorPresenter.userMessage(context, JadeException.NetworkMismatch("x")))
        assertEquals("jade busy", HwErrorPresenter.userMessage(context, JadeException.DeviceLocked()))
        assertEquals("pinserver", HwErrorPresenter.userMessage(context, JadeException.PinServerException("x")))
    }

    @Test
    fun `maps a wrapped jade error through the cause chain`() {
        assertEquals("wrong pin", HwErrorPresenter.userMessage(context, AppError(JadeException.InvalidPin())))
    }

    @Test
    fun `falls back to the trezor presenter for other errors`() {
        assertEquals("trezor busy", HwErrorPresenter.userMessage(context, TrezorException.DeviceBusy()))
        assertEquals("boom", HwErrorPresenter.userMessage(context, AppError("boom"), fallback = "fallback"))
        assertEquals("fallback", HwErrorPresenter.userMessage(context, AppError(""), fallback = "fallback"))
        assertEquals("connect error", HwErrorPresenter.userMessage(context, JadeException.Timeout()))
    }
}
