package to.bitkit.ext

import java.io.File
import kotlin.io.path.exists

fun File.ensureDir() = this.also {
    if (toPath().exists()) return this

    val path = if (extension.isEmpty()) this else parentFile
    if (!path.mkdirs()) throw Error("Cannot create path: $this")
}
