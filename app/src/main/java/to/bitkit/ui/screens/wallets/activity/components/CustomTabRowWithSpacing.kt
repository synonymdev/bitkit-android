package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors

@Composable
fun <T : TabItem> CustomTabRowWithSpacing(
    tabs: ImmutableList<T>,
    currentTabIndex: Int,
    onTabChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = Colors.Brand,
    badgeCount: (T) -> Int? = { null },
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            val safeIndex = currentTabIndex.coerceIn(0, tabs.lastIndex)
            tabs.forEachIndexed { index, tab ->
                val isSelected = tabs[safeIndex] == tab

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    TabLabel(
                        text = tab.uiText,
                        color = if (isSelected) Colors.White else Colors.White50,
                        badgeCount = badgeCount(tab)?.takeIf { it > 0 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableAlpha { onTabChange(tab) }
                            .padding(vertical = 8.dp)
                            .testTag("Tab-${tab.name.lowercase()}")
                    )

                    val animatedColor by animateColorAsState(
                        targetValue = if (isSelected) selectedColor else Colors.White50,
                        animationSpec = tween(
                            durationMillis = 200,
                            easing = FastOutSlowInEasing
                        ),
                        label = "indicatorColor",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(animatedColor)
                    )
                }

                if (index < tabs.size - 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }
}

private val TabBadgeSize = 20.dp
private val TabBadgeGap = 6.dp

@Composable
private fun TabLabel(
    text: String,
    color: Color,
    badgeCount: Int?,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(
            { CaptionB(text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color) },
            {
                badgeCount?.let { count ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(TabBadgeSize)
                            .background(Colors.Brand, CircleShape)
                    ) {
                        CaptionB(text = count.toString(), color = Colors.White)
                    }
                }
            },
        ),
        modifier = modifier
    ) { (labelMeasurables, badgeMeasurables), constraints ->
        val badge = badgeMeasurables.firstOrNull()?.measure(Constraints())
        val reserved = badge?.let { it.width + TabBadgeGap.roundToPx() } ?: 0
        val label = labelMeasurables.first().measure(
            constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - 2 * reserved).coerceAtLeast(0))
        )
        val width = constraints.maxWidth
        val height = maxOf(label.height, TabBadgeSize.roundToPx())

        layout(width, height) {
            val labelX = (width - label.width) / 2
            label.placeRelative(labelX, (height - label.height) / 2)
            badge?.placeRelative(
                labelX + label.width + TabBadgeGap.roundToPx(),
                (height - badge.height) / 2,
            )
        }
    }
}

interface TabItem {
    val name: String
    val uiText: String
        @Composable get
}
