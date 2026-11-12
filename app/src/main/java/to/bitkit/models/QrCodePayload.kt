package to.bitkit.models

data class QrCodePayload(
    val text: String?,
    val rawBytes: ByteArray?,
    /** Read from a nearby device over Bluetooth, which QuickPay must not pay without review. */
    val isNearby: Boolean = false,
)
