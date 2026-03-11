package to.bitkit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.ext.toHex
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

    fun decodeAndStore(data: ByteArray, uri: String): Result<Bitmap> = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: error("Could not decode image blob (${data.size} bytes)")
        store(bitmap, data, uri)
        bitmap
    }

    fun store(bitmap: Bitmap, data: ByteArray, uri: String): Result<Unit> = runCatching {
        memoryCache[uri] = bitmap
        diskPath(uri).writeBytes(data)
    }

    fun clear(): Result<Unit> = runCatching {
        memoryCache.clear()
        diskDir.deleteRecursively()
        diskDir.mkdirs()
    }

    private fun diskPath(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(uri.toByteArray()).toHex()
        return File(diskDir, hash)
    }
}
