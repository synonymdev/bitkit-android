package to.bitkit.wear.utils

open class AppError(
    override val message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class HttpError(message: String, val code: Int) : AppError(message)
