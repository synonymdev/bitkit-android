package to.bitkit.ext

import uniffi.bitkitcore.Activity

val Activity.idValue: String
    get() = when (this) {
        is Activity.Lightning -> v1.id
        is Activity.Onchain -> v1.txId
    }
