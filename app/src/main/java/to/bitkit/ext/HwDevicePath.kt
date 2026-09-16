package to.bitkit.ext

/** Prefix of every Bluetooth hardware wallet device path, shared by both vendors' transports. */
const val BLE_PATH_PREFIX = "ble:"

fun String.isBlePath(): Boolean = startsWith(BLE_PATH_PREFIX)

fun String.bleAddress(): String = removePrefix(BLE_PATH_PREFIX)

fun blePath(address: String): String = "$BLE_PATH_PREFIX$address"
