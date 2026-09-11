package to.bitkit.utils

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object SubscriptionIcon {
    /** Maximum pixel dimension of a subscription's public icon. */
    private const val MAX_DIMENSION = 400

    /** JPEG quality used for public subscription icons. */
    private const val JPEG_QUALITY = 80

    fun load(resolver: ContentResolver, uri: Uri): ByteArray =
        encode(ImageDecoder.createSource(resolver, uri))

    fun compress(bytes: ByteArray): ByteArray = encode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

    private fun encode(source: ImageDecoder.Source): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(MAX_DIMENSION.toFloat() / info.size.width, MAX_DIMENSION.toFloat() / info.size.height, 1f)
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
