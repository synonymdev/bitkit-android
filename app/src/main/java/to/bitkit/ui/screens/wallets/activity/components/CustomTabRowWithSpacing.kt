package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors

@Composable
fun <T : TabItem> CustomTabRowWithSpacing(
    tabs: List<T>,
    currentTabIndex: Int,
    onTabChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = Colors.Brand,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = tabs[currentTabIndex] == tab

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableAlpha { onTabChange(tab) }
                            .padding(vertical = 8.dp)
                            .testTag("Tab-${tab.name.lowercase()}")
                    ) {
                        CaptionB(
                            tab.uiText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) Colors.White else Colors.White50
                        )
                    }

                    val animatedAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.2f,
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing
                        ),
                        label = "indicatorAlpha",
                    )

                    val animatedColor by animateColorAsState(
                        targetValue = if (isSelected) selectedColor else Colors.White,
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing
                        ),
                        label = "indicatorColor",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .height(3.dp)
                            .background(animatedColor.copy(alpha = animatedAlpha))
                    )
                }

                if (index < tabs.size - 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }
}

interface TabItem {
    val name: String
    val uiText: String
        @Composable get
}
