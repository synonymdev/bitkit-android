package to.bitkit.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger
import javax.inject.Inject

private const val TAG = "PubkyImage"

@HiltViewModel
class PubkyImageViewModel @Inject constructor(
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    private val _images = MutableStateFlow<Map<String, PubkyImageState>>(emptyMap())
    val images = _images.asStateFlow()

    fun loadImage(uri: String) {
        val current = _images.value[uri]
        if (current is PubkyImageState.Loaded || current is PubkyImageState.Loading) return

        val cached = pubkyRepo.cachedImage(uri)
        if (cached != null) {
            _images.update { it + (uri to PubkyImageState.Loaded(cached)) }
            return
        }

        _images.update { it + (uri to PubkyImageState.Loading) }
        viewModelScope.launch {
            pubkyRepo.fetchImage(uri)
                .onSuccess { bitmap ->
                    _images.update { it + (uri to PubkyImageState.Loaded(bitmap)) }
                }
                .onFailure {
                    Logger.error("Failed to load pubky image", it, context = TAG)
                    _images.update { it + (uri to PubkyImageState.Failed) }
                }
        }
    }
}

sealed interface PubkyImageState {
    data object Loading : PubkyImageState
    data class Loaded(val bitmap: Bitmap) : PubkyImageState
    data object Failed : PubkyImageState
}

@Composable
fun PubkyImage(
    uri: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val viewModel: PubkyImageViewModel = hiltViewModel()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val state = images[uri]

    LaunchedEffect(uri) {
        viewModel.loadImage(uri)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        when (state) {
            is PubkyImageState.Loaded -> {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            is PubkyImageState.Failed -> {
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
