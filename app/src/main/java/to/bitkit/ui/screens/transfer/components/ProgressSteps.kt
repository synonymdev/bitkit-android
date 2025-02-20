package to.bitkit.ui.screens.transfer.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ProgressSteps(
    steps: List<String>,
    activeStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { index, _ ->
                val isActive = activeStepIndex == index
                val isDone = activeStepIndex > index

                // Step circle
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .then(
                            when {
                                isDone -> Modifier.background(Colors.Purple)
                                else -> Modifier.border(
                                    1.dp,
                                    if (isActive) Colors.Purple else Colors.White32,
                                    CircleShape
                                )
                            }
                        )
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            color = when {
                                isActive -> Colors.Purple
                                else -> Colors.White32
                            }
                        )
                    }
                }

                // Connector line
                if (index < steps.size - 1) {
                    Canvas(
                        modifier = Modifier
                            .width(32.dp)
                            .height(1.dp)
                    ) {
                        drawLine(
                            color = Colors.White32,
                            strokeWidth = 1.dp.toPx(),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(12f, 12f),
                                phase = 0f
                            )
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        BodySSB(text = steps[activeStepIndex], color = Colors.White32)
    }
}

// Preview
@Preview(showBackground = true)
@Composable
fun ProgressStepsPreview() {
    AppThemeSurface {
        val steps = listOf(
            "Step 1",
            "Step 2",
            "Step 3",
            "Step 4",
        )
        ProgressSteps(
            steps = steps,
            activeStepIndex = 1,
            modifier = Modifier.padding(16.dp)
        )
    }
}
