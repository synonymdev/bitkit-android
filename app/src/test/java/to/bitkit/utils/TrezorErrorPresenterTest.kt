package to.bitkit.utils

import android.content.Context
import com.synonym.bitkitcore.TrezorException
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import kotlin.test.assertEquals

class TrezorErrorPresenterTest {
    private val context = mock<Context>()
    private val deviceBusyMessage = "Your Trezor is busy. Unlock it on the device, then try again."
    private val connectErrorMessage = "Could not connect to your Trezor."

    @Before
    fun setUp() {
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(deviceBusyMessage)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn(connectErrorMessage)
    }

    @Test
    fun `userMessage maps typed device busy to unlock prompt`() {
        val message = TrezorErrorPresenter.userMessage(context, AppError(TrezorException.DeviceBusy()))

        assertEquals(deviceBusyMessage, message)
    }

    @Test
    fun `userMessage maps firmware error response to connect guidance`() {
        val message = TrezorErrorPresenter.userMessage(context, AppError("Device error (code 99): Firmware error"))

        assertEquals(connectErrorMessage, message)
    }

    @Test
    fun `userMessage returns error message when present`() {
        assertEquals("connect failed", TrezorErrorPresenter.userMessage(context, AppError("connect failed")))
    }

    @Test
    fun `userMessage uses default fallback when message is blank`() {
        assertEquals(connectErrorMessage, TrezorErrorPresenter.userMessage(context, AppError("")))
    }

    @Test
    fun `userMessage uses custom fallback when message is blank`() {
        assertEquals(
            "custom fallback",
            TrezorErrorPresenter.userMessage(context, AppError(""), fallback = "custom fallback"),
        )
    }
}
