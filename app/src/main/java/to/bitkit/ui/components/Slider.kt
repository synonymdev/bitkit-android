package to.bitkit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KNOB_SIZE_DP = 32

/** Horizontal inset so the knob stays clear of the screen edge and its system back-gesture zone. */
private const val SLIDER_EDGE_INSET_DP = 16
private const val TRACK_HEIGHT_DP = 8
private const val STEP_MARKER_WIDTH_DP = 4
private const val STEP_MARKER_HEIGHT_DP = 16
private const val LABEL_TOP_PADDING_DP = 4

@Suppress("CyclomaticComplexMethod")
@Composable
fun Slider(
    value: Int,
    steps: ImmutableList<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    formatLabel: (Int) -> String = { "$$it" },
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val knobPosition = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var layoutWidthPx by remember { mutableIntStateOf(0) }
    val knobHeightPx = with(density) { KNOB_SIZE_DP.dp.roundToPx() }
    val labelTopPadPx = with(density) { LABEL_TOP_PADDING_DP.dp.roundToPx() }

    val compositionStepPositions = remember(steps, layoutWidthPx) {
        val sliderWidth = layoutWidthPx.toFloat()
        if (sliderWidth <= 0f) {
            emptyList()
        } else {
            val numSteps = (steps.size - 1).coerceAtLeast(1)
            steps.indices.map { index -> (index.toFloat() / numSteps) * sliderWidth }
        }
    }
    val valueIndex = steps.indexOf(value).takeIf { it >= 0 } ?: 0
    val settledX = compositionStepPositions.getOrElse(valueIndex) { 0f }
    val settledXState = rememberUpdatedState(settledX)

    LaunchedEffect(settledX, isDragging) {
        if (!isDragging) {
            knobPosition.snapTo(settledX)
        }
    }

    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { layoutWidthPx = it.width }
            .stepSliderSemantics(
                valueIndex = valueIndex,
                stepCount = steps.size,
                stateDescription = formatLabel(value),
                onIndexChange = { onValueChange(steps[it]) },
            )
    ) { constraints ->
        val width = constraints.maxWidth
        val sliderWidth = width.toFloat()
        val stepPositions = if (sliderWidth <= 0f) {
            emptyList()
        } else {
            val numSteps = (steps.size - 1).coerceAtLeast(1)
            steps.indices.map { index -> (index.toFloat() / numSteps) * sliderWidth }
        }
        val knobX = if (isDragging) knobPosition.value else stepPositions.getOrElse(valueIndex) { 0f }

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

        val trackPlaceable = subcompose(StepSliderSlot.Track) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemGestureExclusion()
                    .height(KNOB_SIZE_DP.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KNOB_SIZE_DP.dp)
                        .pointerInput(stepPositions, steps, sliderWidth) {
                            detectTapGestures { offset ->
                                val (closestStep, closestIndex) = findClosestStep(offset.x)
                                coroutineScope.launch {
                                    knobPosition.snapTo(settledXState.value)
                                    isDragging = true
                                    knobPosition.animateTo(
                                        targetValue = closestStep,
                                        animationSpec = SpringSpec(dampingRatio = 0.8f, stiffness = 400f),
                                    )
                                    isDragging = false
                                }
                                onValueChange(steps[closestIndex])
                            }
                        }
                ) {
                    val trackY = center.y
                    val trackHeight = density.run { TRACK_HEIGHT_DP.dp.toPx() }
                    val cornerRadius = density.run { 3.dp.toPx() }

                    drawRoundRect(
                        color = Colors.Green32,
                        topLeft = Offset(0f, trackY - trackHeight / 2),
                        size = Size(size.width, trackHeight),
                        cornerRadius = CornerRadius(cornerRadius),
                    )

                    if (knobX > 0f) {
                        drawRoundRect(
                            color = Colors.Green,
                            topLeft = Offset(0f, trackY - trackHeight / 2),
                            size = Size(knobX, trackHeight),
                            cornerRadius = CornerRadius(cornerRadius),
                        )
                    }

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

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (knobX - with(density) { KNOB_SIZE_DP.dp.toPx() / 2 }).roundToInt(),
                                y = 0,
                            )
                        }
                        .size(KNOB_SIZE_DP.dp)
                        .pointerInput(stepPositions, steps, sliderWidth) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    coroutineScope.launch {
                                        knobPosition.snapTo(settledXState.value)
                                        isDragging = true
                                    }
                                },
                                onDragEnd = {
                                    val (closestStep, closestIndex) = findClosestStep(knobPosition.value)
                                    coroutineScope.launch {
                                        knobPosition.animateTo(
                                            targetValue = closestStep,
                                            animationSpec = SpringSpec(dampingRatio = 0.8f, stiffness = 400f),
                                        )
                                        isDragging = false
                                    }
                                    onValueChange(steps[closestIndex])
                                },
                                onDragCancel = {
                                    coroutineScope.launch {
                                        knobPosition.snapTo(settledXState.value)
                                        isDragging = false
                                    }
                                },
                            ) { _, dragAmount ->
                                coroutineScope.launch {
                                    val newPosition = (knobPosition.value + dragAmount)
                                        .coerceIn(0f, sliderWidth)
                                    knobPosition.snapTo(newPosition)
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(KNOB_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(Colors.Green)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Colors.White)
                                .align(Alignment.Center)
                        )
                    }
                }
            }
        }.first().measure(Constraints.fixed(width, knobHeightPx))

        val labelsPlaceable = subcompose(StepSliderSlot.Labels) {
            StepSliderLabels(
                steps = steps,
                formatLabel = formatLabel,
            )
        }.first().measure(Constraints.fixedWidth(width))

        val height = trackPlaceable.height + labelTopPadPx + labelsPlaceable.height
        layout(width, height) {
            trackPlaceable.placeRelative(0, 0)
            labelsPlaceable.placeRelative(0, trackPlaceable.height + labelTopPadPx)
        }
    }
}

private enum class StepSliderSlot { Track, Labels }

private fun Modifier.stepSliderSemantics(
    valueIndex: Int,
    stepCount: Int,
    stateDescription: String,
    onIndexChange: (Int) -> Unit,
): Modifier = semantics {
    this.stateDescription = stateDescription
    val lastIndex = (stepCount - 1).coerceAtLeast(0)
    progressBarRangeInfo = ProgressBarRangeInfo(
        current = valueIndex.toFloat(),
        range = 0f..lastIndex.toFloat(),
        steps = (stepCount - 2).coerceAtLeast(0),
    )
    setProgress { target ->
        onIndexChange(target.roundToInt().coerceIn(0, lastIndex))
        true
    }
}

@Composable
private fun StepSliderLabels(
    steps: ImmutableList<Int>,
    formatLabel: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            steps.forEach { step ->
                Caption13Up(
                    text = formatLabel(step),
                    color = Colors.White64,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(KNOB_SIZE_DP.dp)
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(Constraints())
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = constraints.maxWidth
        val numSteps = (placeables.size - 1).coerceAtLeast(1)

        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val centerX = (index.toFloat() / numSteps) * width
                val x = (centerX - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}

/**
 * Continuous slider over a [min]..[max] range, styled to match [Slider] (same track and
 * knob) but without discrete steps. Used to pick a transfer amount within its allowed limits.
 */
@Composable
fun AmountSlider(
    value: Long,
    min: Long,
    max: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var sliderWidth by remember { mutableIntStateOf(0) }
    val knobPosition = remember { Animatable(0f) }
    val span = (max - min).coerceAtLeast(1)

    fun fractionFor(v: Long): Float = ((v - min).toFloat() / span).coerceIn(0f, 1f)

    fun valueFor(logicalPositionPx: Float): Long {
        if (sliderWidth == 0) return min
        val fraction = (logicalPositionPx / sliderWidth).coerceIn(0f, 1f)
        return (min + (fraction * span).roundToInt()).coerceIn(min, max)
    }

    // Keep the knob in sync with external value changes (and initial layout).
    LaunchedEffect(value, sliderWidth) {
        if (sliderWidth > 0) {
            knobPosition.snapTo(fractionFor(value) * sliderWidth)
        }
    }

    val widthPx = sliderWidth.toFloat()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .systemGestureExclusion()
            .padding(horizontal = SLIDER_EDGE_INSET_DP.dp)
            .height(KNOB_SIZE_DP.dp)
            .onGloballyPositioned { sliderWidth = it.size.width }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(KNOB_SIZE_DP.dp)
                .pointerInput(sliderWidth, min, max) {
                    detectTapGestures { offset ->
                        val v = valueFor(offset.x)
                        coroutineScope.launch { knobPosition.snapTo(fractionFor(v) * widthPx) }
                        onValueChange(v)
                    }
                }
        ) {
            val trackY = center.y
            val trackHeight = density.run { TRACK_HEIGHT_DP.dp.toPx() }
            val cornerRadius = density.run { 3.dp.toPx() }

            // Inactive track
            drawRoundRect(
                color = Colors.Green32,
                topLeft = Offset(0f, trackY - trackHeight / 2),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(cornerRadius),
            )
            // Active track
            if (knobPosition.value > 0) {
                drawRoundRect(
                    color = Colors.Green,
                    topLeft = Offset(0f, trackY - trackHeight / 2),
                    size = Size(knobPosition.value, trackHeight),
                    cornerRadius = CornerRadius(cornerRadius),
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
                .pointerInput(sliderWidth, min, max) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        coroutineScope.launch {
                            val newPosition = (knobPosition.value + dragAmount)
                                .coerceIn(0f, widthPx)
                            knobPosition.snapTo(newPosition)
                            onValueChange(valueFor(newPosition))
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .size(KNOB_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(Colors.Green)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Colors.White)
                        .align(Alignment.Center)
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
            Slider(
                value = value,
                steps = persistentListOf(1, 5, 10, 20, 50),
                onValueChange = { value = it },
            )
        }
    }
}

@Preview
@Composable
private fun PreviewUnitStops() {
    AppThemeSurface {
        var value by remember { mutableLongStateOf(72_000L) }
        Column(modifier = Modifier.padding(32.dp)) {
            AmountSlider(
                value = value,
                min = 50_000L,
                max = 100_000L,
                onValueChange = { value = it },
            )
        }
    }
}

@Preview
@Composable
private fun PreviewVerticalStack() {
    AppThemeSurface {
        var dollars by remember { mutableIntStateOf(1) }
        var times by remember { mutableIntStateOf(5) }
        Column(modifier = Modifier.padding(32.dp)) {
            Slider(
                value = dollars,
                steps = persistentListOf(1, 5, 10, 20, 50),
                onValueChange = { dollars = it },
            )
            VerticalSpacer(32.dp)
            Slider(
                value = times,
                steps = persistentListOf(1, 3, 5, 10, 50),
                onValueChange = { times = it },
                formatLabel = { "$it×" },
            )
        }
    }
}
