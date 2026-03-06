package to.bitkit.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.bitkit.R
import to.bitkit.data.PubkyImageCache
import to.bitkit.services.PubkyService
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger

private const val TAG = "PubkyImage"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PubkyImageEntryPoint {
    fun pubkyService(): PubkyService
    fun pubkyImageCache(): PubkyImageCache
}

@Composable
fun PubkyImage(
    uri: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, PubkyImageEntryPoint::class.java)
    }
    val cache = remember { entryPoint.pubkyImageCache() }
    val service = remember { entryPoint.pubkyService() }
    var bitmap by remember(uri) { mutableStateOf(cache.memoryImage(uri)) }
    var hasFailed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        hasFailed = false

        if (bitmap != null) return@LaunchedEffect

        runCatching {
            withContext(Dispatchers.IO) {
                cache.image(uri)?.let { return@withContext it }

                val data = service.fetchFile(uri)
                val blobData = resolveImageData(data, service)
                val image = BitmapFactory.decodeByteArray(blobData, 0, blobData.size)
                    ?: error("Could not decode image blob (${blobData.size} bytes)")
                cache.store(image, blobData, uri)
                image
            }
        }.onSuccess {
            bitmap = it
        }.onFailure {
            Logger.error("Failed to load pubky image", it, context = TAG)
            hasFailed = true
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        val currentBitmap = bitmap
        when {
            currentBitmap != null -> {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            hasFailed -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .matchParentSize()
                        .background(Colors.Gray5, CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_user_square),
                        contentDescription = null,
                        tint = Colors.White32,
                        modifier = Modifier.size(size / 2)
                    )
                }
            }
            else -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Colors.White32,
                    modifier = Modifier.size(size / 3)
                )
            }
        }
    }
}

private const val ALLOWED_SCHEME = "pubky://"

private suspend fun resolveImageData(data: ByteArray, service: PubkyService): ByteArray {
    return runCatching {
        val json = JSONObject(String(data))
        val src = json.optString("src", "")
        if (src.isNotEmpty() && src.startsWith(ALLOWED_SCHEME)) {
            Logger.debug("File descriptor found, fetching blob from: $src", context = TAG)
            service.fetchFile(src)
        } else {
            data
        }
    }.getOrDefault(data)
}
