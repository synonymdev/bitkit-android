package to.bitkit.shared

inline fun <reified T : Any> nameOf(): String = "${T::class.simpleName}"
