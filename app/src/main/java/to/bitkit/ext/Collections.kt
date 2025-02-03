package to.bitkit.ext

fun Map<String, String>.containsKeys(vararg keys: String): Boolean {
    return keys.all { this.containsKey(it) }
}
