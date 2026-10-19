package to.bitkit.ext

/** Vendor-neutral views over the Trezor and Jade error predicates, for code shared by every vendor. */
fun Throwable.isHwUserCancellation(): Boolean = isTrezorUserCancellation() || isJadeUserCancellation()

fun Throwable.isHwDeviceBusy(): Boolean = isTrezorDeviceBusy() || isJadeDeviceBusy()

fun Throwable.isHwFirmwareError(): Boolean = isTrezorFirmwareError() || isJadeFirmwareError()

fun Throwable.isHwSessionFailure(): Boolean = isTrezorSessionFailure() || isJadeSessionFailure()
