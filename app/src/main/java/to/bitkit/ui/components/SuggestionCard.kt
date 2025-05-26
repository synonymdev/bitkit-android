package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Devices.TV_1080p
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.Suggestion
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun SuggestionCard(
    modifier: Modifier = Modifier,
    gradientColor: Color,
    title: String,
    description: String,
    @DrawableRes icon: Int,
    duration: Duration? = null,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(152.dp)
            .clip(ShapeDefaults.Large)
            .gradientBackground(gradientColor.copy(alpha = 0.30f))
            .clickableAlpha { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.weight(1f)
                )

                if (duration == null) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = null,
                            tint = Colors.White,
                        )
                    }
                }
            }

            Headline20(
                text = AnnotatedString(title),
                color = Colors.White,
            )

            CaptionB(
                text = description,
                color = Colors.White,
            )
        }
    }
}

@Preview(device = TV_1080p)
@Composable
private fun Preview() {
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Suggestion.entries.map { item ->
            SuggestionCard(
                gradientColor = item.color,
                title = stringResource(item.title),
                description = stringResource(item.description),
                icon = item.icon,
                onClose = {},
                onClick = {},
                duration = 5.seconds.takeIf { item == Suggestion.TRANSFER_PENDING }
            )

        }
    }
}


