package to.bitkit.ext

import com.synonym.bitkitcore.JadeException
import com.synonym.bitkitcore.TrezorException
import org.junit.Test
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HwExceptionExtTest {

    @Test
    fun `jade user cancellation is detected through the cause chain`() {
        assertTrue(AppError(JadeException.UserCancelled()).isJadeUserCancellation())
        assertTrue(AppError(JadeException.UserCancelled()).isHwUserCancellation())
        assertFalse(AppError(JadeException.DeviceBusy()).isJadeUserCancellation())
    }

    @Test
    fun `a locked or busy jade counts as busy`() {
        assertTrue(JadeException.DeviceLocked().isJadeDeviceBusy())
        assertTrue(JadeException.DeviceBusy().isHwDeviceBusy())
        assertTrue(TrezorException.DeviceBusy().isHwDeviceBusy())
        assertFalse(JadeException.Timeout().isHwDeviceBusy())
    }

    @Test
    fun `outdated jade firmware is a firmware error`() {
        assertTrue(JadeException.UnsupportedFirmware("0.1.0", "1.0.34").isJadeFirmwareError())
        assertTrue(AppError(JadeException.UnsupportedFirmware("0.1.0", "1.0.34")).isHwFirmwareError())
        assertFalse(JadeException.InvalidPin().isHwFirmwareError())
    }

    @Test
    fun `transport level jade failures are session failures`() {
        assertTrue(JadeException.DeviceDisconnected().isJadeSessionFailure())
        assertTrue(JadeException.Timeout().isHwSessionFailure())
        assertTrue(AppError(JadeException.TransportException("usb")).isHwSessionFailure())
        assertTrue(TrezorException.DeviceDisconnected().isHwSessionFailure())
        assertFalse(JadeException.InvalidPin().isHwSessionFailure())
        assertFalse(JadeException.AddressMismatch("a", "b").isHwSessionFailure())
    }
}
