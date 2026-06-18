package to.bitkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport a hardware-wallet device is paired over. */
@Serializable
enum class TransportType {
    @SerialName("bluetooth")
    BLUETOOTH,

    @SerialName("usb")
    USB,
}
