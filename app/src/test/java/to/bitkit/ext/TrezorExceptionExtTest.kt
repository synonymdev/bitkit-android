package to.bitkit.ext

import com.synonym.bitkitcore.TrezorException
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TrezorExceptionExtTest {
    @Test
    fun `isTrezorUserCancellation returns true for user cancelled exceptions`() {
        assertTrue(TrezorException.UserCancelled().isTrezorUserCancellation())
        assertTrue(TrezorException.PinCancelled().isTrezorUserCancellation())
        assertTrue(TrezorException.PassphraseCancelled().isTrezorUserCancellation())
    }

    @Test
    fun `isTrezorUserCancellation walks the cause chain`() {
        val error = AppError(TrezorException.UserCancelled())

        assertTrue(error.isTrezorUserCancellation())
    }

    @Test
    fun `isTrezorUserCancellation returns false for other errors`() {
        assertFalse(AppError("sign failed").isTrezorUserCancellation())
        assertFalse(TrezorException.Timeout().isTrezorUserCancellation())
    }

    @Test
    fun `isTrezorDeviceBusy returns true for device busy exceptions`() {
        assertTrue(TrezorException.DeviceBusy().isTrezorDeviceBusy())
    }

    @Test
    fun `isTrezorDeviceBusy walks the cause chain`() {
        val error = AppError(TrezorException.DeviceBusy())

        assertTrue(error.isTrezorDeviceBusy())
    }

    @Test
    fun `isTrezorDeviceBusy returns false for other errors`() {
        assertFalse(TrezorException.Timeout().isTrezorDeviceBusy())
        assertFalse(TrezorException.UserCancelled().isTrezorDeviceBusy())
        assertFalse(AppError("sign failed").isTrezorDeviceBusy())
    }
}
