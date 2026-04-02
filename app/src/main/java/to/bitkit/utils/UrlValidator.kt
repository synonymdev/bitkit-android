package to.bitkit.utils

fun interface UrlValidator {
    suspend fun validate(url: String): Result<Unit>
}
