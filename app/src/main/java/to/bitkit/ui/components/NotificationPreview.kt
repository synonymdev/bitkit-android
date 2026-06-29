package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

/** Degrees to rotate the right chevron so it points downward (expand-more) in the preview. */
private const val CHEVRON_EXPAND_ROTATION_DEGREES = 90f

@Composable
fun NotificationPreview(
    enabled: Boolean,
    title: String,
    description: String,
    showDetails: Boolean,
    modifier: Modifier = Modifier,
    time: String = "5m",
) {
    Box(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(Shapes.extraSmall)
                .background(Colors.White16)
                .padding(16.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BodySSB(text = title, color = Colors.White)
                    Caption(text = "•", color = Colors.White64)
                    Caption(text = time, color = Colors.White64)
                }

                val bodyText = when (showDetails) {
                    true -> description
                    else -> stringResource(R.string.notification__received__body_hidden)
                }
                AnimatedContent(targetState = bodyText) { text ->
                    BodyS(text = text, color = Colors.White80)
                }
            }

            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = Colors.White64,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(CHEVRON_EXPAND_ROTATION_DEGREES)
            )
        }

        if (!enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(Shapes.extraSmall)
                    .background(Colors.Black70)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            NotificationPreview(
                enabled = true,
                title = "Payment Received",
                description = "₿ 21 000 ($21.00)",
                showDetails = true,
                modifier = Modifier.fillMaxWidth()
            )
            NotificationPreview(
                enabled = false,
                title = "Payment Received",
                description = "₿ 21 000 ($21.00)",
                showDetails = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
