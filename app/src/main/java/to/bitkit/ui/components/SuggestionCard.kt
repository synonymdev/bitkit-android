package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.Suggestion
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

@Composable
fun SuggestionCard(
    modifier: Modifier = Modifier,
    gradientColor: Color,
    title: String,
    description: String,
    @DrawableRes icon: Int,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(152.dp)
            .gradientBackground(gradientColor)
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
                    modifier = Modifier.size(95.24.dp)
                )

                IconButton(
                    onClick = onClose
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = null
                    )
                }
            }

            Headline(
                text = AnnotatedString(title),
                color = Colors.White,
            )

            BodyM(
                text = description,
                color = Colors.White,
            )
        }
    }
}

@Preview()
@Composable
private fun Preview() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Suggestion.entries.map { item ->
            SuggestionCard(
                gradientColor = item.color,
                title = stringResource(item.title),
                description = stringResource(item.description),
                icon = item.icon,
                onClose = {},
                onClick = {}
            )

        }
    }
}


