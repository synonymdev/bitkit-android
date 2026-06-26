package to.bitkit.ext

import com.synonym.bitkitcore.TrezorTransportType
import to.bitkit.models.TransportType

fun TrezorTransportType.toTransportType(): TransportType = when (this) {
    TrezorTransportType.BLUETOOTH -> TransportType.BLUETOOTH
    TrezorTransportType.USB -> TransportType.USB
}
