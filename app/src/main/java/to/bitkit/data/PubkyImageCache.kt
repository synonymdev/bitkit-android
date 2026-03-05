package to.bitkit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PubkyImageCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private val diskDir: File = File(context.cacheDir, "pubky-images").also { it.mkdirs() }

    fun memoryImage(uri: String): Bitmap? = memoryCache[uri]

    fun image(uri: String): Bitmap? {
        memoryCache[uri]?.let { return it }

        val file = diskPath(uri)
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            memoryCache[uri] = bitmap
            return bitmap
        }
        return null
    }

    fun store(bitmap: Bitmap, data: ByteArray, uri: String) {
        memoryCache[uri] = bitmap
        runCatching { diskPath(uri).writeBytes(data) }
    }

    fun clear() {
        memoryCache.clear()
        runCatching {
            diskDir.deleteRecursively()
            diskDir.mkdirs()
        }
    }

    private fun diskPath(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(diskDir, hash)
    }
}
