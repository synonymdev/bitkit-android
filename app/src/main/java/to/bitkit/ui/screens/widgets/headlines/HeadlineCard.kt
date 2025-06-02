package to.bitkit.ui.screens.widgets.headlines

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun HeadlineCard(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean = true,
    showTime: Boolean = true,
    showSource: Boolean = true,
    time: String,
    headline: String,
    source: String,
    link: String
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                context.startActivity(intent)
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_newspaper),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("widget_title_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__news__name),
                        modifier = Modifier.testTag("widget_title_text")
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showTime && time.isNotEmpty()) {
                BodyM(
                    text = time,
                    modifier = Modifier.testTag("time_text")
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            BodyMB(
                text = headline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("headline_text")
            )

            if (showSource) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("source_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodyS(
                        text = stringResource(R.string.widgets__widget__source),
                        color = Colors.White64,
                        modifier = Modifier.testTag("source_label")
                    )
                    BodyS(
                        text = source,
                        color = Colors.White64,
                        modifier = Modifier.testTag("source_text")
                    )
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
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showWidgetTitle = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showTime = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showWidgetTitle = false,
                showTime = false,
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooooow",
                source = "bitcoinmagazine.com",
                link = ""
            )
        }
    }
}
