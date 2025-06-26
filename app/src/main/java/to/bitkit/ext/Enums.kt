package to.bitkit.ext

inline fun <reified T : Enum<T>> getEnumValueOf(name: String): Result<T> {
    return runCatching {
        enumValues<T>().first { it.name.equals(name, ignoreCase = true) }
    }
}
