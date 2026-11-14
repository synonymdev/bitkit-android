package to.bitkit.ui.shared.effects

/** [LOCATION_OFF] applies to scanning before Android 12, where BLE scans need location services on. */
enum class BluetoothState { UNAVAILABLE, NEEDS_PERMISSION, OFF, LOCATION_OFF, ON }
