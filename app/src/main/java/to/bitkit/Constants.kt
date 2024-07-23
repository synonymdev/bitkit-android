package to.bitkit

import org.bitcoindevkit.Network

internal const val _DEV = "_DEV"
internal const val _FCM = "_FCM"
internal const val _LDK = "_LDK"
internal const val _BDK = "_BDK"

internal val BDK_NETWORK = Network.REGTEST
internal val LDK_NETWORK get() = org.ldk.enums.Network.LDKNetwork_Regtest
