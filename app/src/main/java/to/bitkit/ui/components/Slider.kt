package to.bitkit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KNOB_SIZE_DP = 32
private const val TRACK_HEIGHT_DP = 8
private const val STEP_MARKER_WIDTH_DP = 4
private const val STEP_MARKER_HEIGHT_DP = 16

@Composable
fun StepSlider(
    value: Int,
    steps: List<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var sliderWidth by remember { mutableIntStateOf(0) }
    val knobPosition = remember { Animatable(0f) }

    // Calculate step positions (evenly spaced)
    val stepPositions = remember(steps, sliderWidth) {
        if (sliderWidth == 0) emptyList()
        else steps.indices.map { index ->
            val numSteps = (steps.size - 1).coerceAtLeast(1)
            (index.toFloat() / numSteps) * sliderWidth
        }
    }

    // Initialize knob position when value changes
    LaunchedEffect(value, stepPositions) {
        if (stepPositions.isNotEmpty()) {
            val valueIndex = steps.indexOf(value)
            if (valueIndex >= 0) {
                knobPosition.snapTo(stepPositions[valueIndex])
            }
        }
    }

    // Find closest step position
    fun findClosestStep(currentPosition: Float): Pair<Float, Int> {
        if (stepPositions.isEmpty()) return 0f to 0

        var closestPosition = stepPositions[0]
        var closestIndex = 0
        var minDistance = abs(currentPosition - stepPositions[0])

        stepPositions.forEachIndexed { index, position ->
            val distance = abs(currentPosition - position)
            if (distance < minDistance) {
                minDistance = distance
                closestPosition = position
                closestIndex = index
            }
        }

        return closestPosition to closestIndex
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                sliderWidth = coordinates.size.width
            }
    ) {
        // Track and step markers
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(KNOB_SIZE_DP.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val (closestStep, closestIndex) = findClosestStep(offset.x)
                        coroutineScope.launch {
                            knobPosition.animateTo(
                                targetValue = closestStep,
                                animationSpec = SpringSpec(dampingRatio = 0.8f, stiffness = 400f),
                            )
                        }
                        onValueChange(steps[closestIndex])
                    }
                }
        ) {
            val trackY = center.y
            val trackHeight = density.run { TRACK_HEIGHT_DP.dp.toPx() }
            val cornerRadius = density.run { 3.dp.toPx() }

            // Draw inactive track
            drawRoundRect(
                color = Colors.Green32,
                topLeft = Offset(0f, trackY - trackHeight / 2),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(cornerRadius),
            )

            // Draw active track
            val activeWidth = knobPosition.value
            if (activeWidth > 0) {
                drawRoundRect(
                    color = Colors.Green,
                    topLeft = Offset(0f, trackY - trackHeight / 2),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = CornerRadius(cornerRadius),
                )
            }

            // Draw step markers
            val markerWidth = density.run { STEP_MARKER_WIDTH_DP.dp.toPx() }
            val markerHeight = density.run { STEP_MARKER_HEIGHT_DP.dp.toPx() }
            val markerRadius = density.run { 2.5.dp.toPx() }

            stepPositions.forEach { position ->
                drawRoundRect(
                    color = Colors.White,
                    topLeft = Offset(position - markerWidth / 2, trackY - markerHeight / 2),
                    size = Size(markerWidth, markerHeight),
                    cornerRadius = CornerRadius(markerRadius),
                )
            }
        }

        // Knob
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (knobPosition.value - with(density) { KNOB_SIZE_DP.dp.toPx() / 2 }).roundToInt(),
                        y = 0,
                    )
                }
                .size(KNOB_SIZE_DP.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { _ ->
                            // No action needed on drag start
                        },
                        onDragEnd = {
                            val (closestStep, closestIndex) = findClosestStep(knobPosition.value)
                            coroutineScope.launch {
                                knobPosition.animateTo(
                                    targetValue = closestStep,
                                    animationSpec = SpringSpec(dampingRatio = 0.8f, stiffness = 400f),
                                )
                            }
                            onValueChange(steps[closestIndex])
                        },
                    ) { _, dragAmount ->
                        coroutineScope.launch {
                            val newPosition = (knobPosition.value + dragAmount.x)
                                .coerceIn(0f, sliderWidth.toFloat())
                            knobPosition.snapTo(newPosition)
                        }
                    }
                }
        ) {
            // Outer green circle
            Box(
                modifier = Modifier
                    .size(KNOB_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(Colors.Green)
            ) {
                // Inner white circle
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Colors.White)
                        .align(Alignment.Center)
                )
            }
        }

        // Step labels
        steps.forEachIndexed { index, step ->
            if (stepPositions.isNotEmpty() && index < stepPositions.size) {
                Caption13Up(
                    text = "$$step",
                    color = Colors.White64,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(KNOB_SIZE_DP.dp)
                        .offset {
                            IntOffset(
                                x = (stepPositions[index] - with(density) { KNOB_SIZE_DP.dp.toPx() / 2 }).roundToInt(),
                                y = with(density) { (KNOB_SIZE_DP.dp + 4.dp).toPx() }.roundToInt(),
                            )
                        }
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        var value by remember { mutableIntStateOf(10) }
        Column(modifier = Modifier.padding(32.dp)) {
            StepSlider(
                value = value,
                steps = listOf(1, 5, 10, 20, 50),
                onValueChange = { value = it },
            )
        }
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(32.dp)) {
            StepSlider(
                value = 5,
                steps = listOf(1, 2, 5, 10),
                onValueChange = {},
            )
        }
    }
}
