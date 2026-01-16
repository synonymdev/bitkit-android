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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

@Composable
fun NotificationPreview(
    enabled: Boolean,
    title: String,
    description: String,
    showDetails: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(Shapes.medium)
                .background(Colors.White80)
                .padding(9.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                modifier = Modifier
                    .size(38.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                BodySSB(text = title, color = Colors.Black)
                val textDescription = when (showDetails) {
                    true -> description
                    else -> stringResource(R.string.notification__received__body_hidden)
                }
                AnimatedContent(targetState = textDescription) { text ->
                    Footnote(text = text, color = Colors.Gray3)
                }
            }

            Caption("3m ago", color = Colors.Gray2)
        }

        if (!enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(Shapes.medium)
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
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            NotificationPreview(
                enabled = true,
                title = "Payment Received",
                description = "₿ 21 000",
                showDetails = true,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(16.dp)
            NotificationPreview(
                enabled = false,
                title = "Payment Received",
                description = "₿ 21 000",
                showDetails = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
