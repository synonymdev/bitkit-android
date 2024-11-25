package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.models.Toast
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Blue500
import to.bitkit.ui.theme.Green500
import to.bitkit.ui.theme.Orange500
import to.bitkit.ui.theme.Purple500
import to.bitkit.ui.theme.Red500

@Composable
fun ToastView(
    toast: Toast,
    onDismiss: () -> Unit,
) {
    val tintColor = when (toast.type) {
        Toast.ToastType.SUCCESS -> Green500
        Toast.ToastType.INFO -> Blue500
        Toast.ToastType.LIGHTNING -> Purple500
        Toast.ToastType.WARNING -> Orange500
        Toast.ToastType.ERROR -> Red500
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
            .background(verticalGradient(listOf(tintColor, Color.Black), startY = -100f), RoundedCornerShape(8.dp))
            .border(1.dp, tintColor, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toast.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = tintColor,
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = toast.description,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                )
            }
            if (!toast.autoHide) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastHost(
    toast: Toast?,
    onDismiss: () -> Unit,
) {
    AnimatedContent(
        targetState = toast,
        transitionSpec = {
            (fadeIn() + slideInVertically { -it })
                .togetherWith(fadeOut() + slideOutVertically { -it })
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopCenter,
        label = "toastAnimation",
    ) {
        if (it != null) {
            ToastView(toast = it, onDismiss = onDismiss)
        }
    }
}

@Composable
fun ToastOverlay(
    toast: Toast?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    ) {
        ToastHost(toast = toast, onDismiss = onDismiss)
    }
}

@Preview
@Composable
private fun ToastViewPreview() {
    AppThemeSurface {
        ToastView(
            toast = Toast(
                type = Toast.ToastType.INFO,
                title = "Info Toast",
                description = "This is a toast message.",
                autoHide = true,
                visibilityTime = 3000L
            ),
            onDismiss = {}
        )
    }
}
