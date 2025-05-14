package to.bitkit.ext

import uniffi.bitkitcore.Activity

fun Activity.rawId(): String = when (this) {
    is Activity.Lightning -> v1.id
    is Activity.Onchain -> v1.id
}
