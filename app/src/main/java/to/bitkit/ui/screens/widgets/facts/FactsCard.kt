package to.bitkit.ui.screens.widgets.facts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Title
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun FactsCard(
    headline: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Colors.White10,
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(backgroundColor)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Title(
                text = headline,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("headline_text")
            )
            BitcoinBadge()
        }
    }
}

@Composable
fun FactsCardSmall(
    headline: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Colors.White10,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WidgetCardDimens.COMPACT_CARD_SIZE.height)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BodyMSB(
                text = headline,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("headline_text")
            )
            BitcoinBadge(modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun BitcoinBadge(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Colors.Bitcoin)
            .testTag("bitcoin_badge")
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bitcoin),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp)
        )
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
            FactsCard(
                headline = "Bitcoin doesn’t need your personal information",
            )
            FactsCard(
                headline = "Priced in Bitcoin, products can become cheaper over time.",
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
            FactsCardSmall(
                headline = "Bitcoin doesn’t need your personal information",
                modifier = Modifier.weight(1f)
            )
            FactsCardSmall(
                headline = "Priced in Bitcoin",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
