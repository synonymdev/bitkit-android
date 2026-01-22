package to.bitkit.ext

import to.bitkit.utils.AppError
import java.io.File
import kotlin.io.path.exists

fun File.ensureDir(): File = this.also {
    if (toPath().exists()) return this

    val path = if (extension.isEmpty()) this else parentFile
    if (!path.mkdirs()) throw AppError("Cannot create path: $this")
}
