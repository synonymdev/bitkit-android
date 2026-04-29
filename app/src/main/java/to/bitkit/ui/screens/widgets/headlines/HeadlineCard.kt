package to.bitkit.ui.screens.widgets.headlines

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Title
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun HeadlineCard(
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    showSource: Boolean = true,
    time: String,
    headline: String,
    source: String,
    link: String,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
            .clickableAlpha {
                if (link.isEmpty()) return@clickableAlpha
                val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                context.startActivity(intent)
            }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Title(
                text = headline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("headline_text")
            )

            val timeVisible = showTime && time.isNotEmpty()
            if (showSource || timeVisible) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("source_row")
                ) {
                    if (showSource) {
                        BodySSB(
                            text = source,
                            color = Colors.Brand,
                            modifier = Modifier.testTag("source_text")
                        )
                    }
                    if (timeVisible) {
                        BodySSB(
                            text = time,
                            color = Colors.White64,
                            modifier = Modifier.testTag("time_text")
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeadlineCardSmall(
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    time: String,
    headline: String,
    link: String,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(WidgetCardDimens.COMPACT_CARD_SIZE)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
            .clickableAlpha {
                if (link.isEmpty()) return@clickableAlpha
                val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                context.startActivity(intent)
            }
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Title(
                text = headline,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("headline_text")
            )

            if (showTime && time.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("source_row")
                ) {
                    BodySSB(
                        text = time,
                        color = Colors.White64,
                        modifier = Modifier.testTag("time_text")
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWide() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            @Suppress("SpellCheckingInspection")
            HeadlineCard(
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline to test the text overflooooow",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showTime = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline",
                source = "bitcoinmagazine.com",
                link = ""
            )
            HeadlineCard(
                showTime = false,
                showSource = false,
                time = "21 minutes ago",
                headline = "How Bitcoin changed El Salvador in more ways a big headline",
                source = "bitcoinmagazine.com",
                link = ""
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            HeadlineCardSmall(
                time = "21 min ago",
                headline = "How Bitcoin changed El Salvador in more ways",
                link = ""
            )
            HeadlineCardSmall(
                showTime = false,
                time = "21 min ago",
                headline = "How Bitcoin changed El Salvador",
                link = ""
            )
        }
    }
}
