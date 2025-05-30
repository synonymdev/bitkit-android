package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.theme.Colors

@Composable
fun HeadlineCard(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean = true,
    showTime: Boolean = true,
    showSource: Boolean = true,
    time: String,
    headline: String,
    source: String
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_newspaper),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(stringResource(R.string.widgets__news__name))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showTime && time.isNotEmpty()) {
                BodyM(text = time)
                Spacer(modifier = Modifier.height(16.dp))
            }

            BodyMB(text = headline, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (showSource) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodyS(text = stringResource(R.string.widgets__widget__source), color = Colors.White64)
                    BodyS(text = source, color = Colors.White64)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeadlineCard(
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com"
            )
            HeadlineCard(
                showWidgetTitle = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com"
            )
            HeadlineCard(
                showTime = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com"
            )
            HeadlineCard(
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com"
            )
            HeadlineCard(
                showWidgetTitle = false,
                showTime = false,
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com"
            )
        }
    }
}
