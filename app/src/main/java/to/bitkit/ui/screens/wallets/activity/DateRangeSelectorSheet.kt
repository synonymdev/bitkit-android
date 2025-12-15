@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.wallets.activity

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import to.bitkit.R
import to.bitkit.ext.CalendarConstants
import to.bitkit.ext.DatePattern
import to.bitkit.ext.endOfDay
import to.bitkit.ext.getDaysInMonth
import to.bitkit.ext.isDateInRange
import to.bitkit.ext.minusMonths
import to.bitkit.ext.plusMonths
import to.bitkit.ext.toMonthYearString
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock.System.now
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Animation
private const val SWIPE_THRESHOLD_DP = 100
private const val FADE_DURATION_MILLIS = 200
private const val SLIDE_OFFSET_PX = 1000f
private const val INITIAL_ALPHA = 1f
private const val TRANSPARENT_ALPHA = 0f
private const val INITIAL_OFFSET = 0f

// Month navigation
private const val NEXT_MONTH = 1
private const val PREVIOUS_MONTH = -1

// Day view styling
private const val DAY_ASPECT_RATIO = 1f
private const val ROUNDED_CORNER_PERCENT = 50
private const val NO_CORNER_RADIUS = 0
private const val TODAY_INDICATOR_ALPHA = 0.1f

@Composable
fun DateRangeSelectorSheet() {
    val activityListVM = activityListViewModel ?: return
    val app = appViewModel ?: return

    val startDate by activityListVM.startDate.collectAsState()
    val endDate by activityListVM.endDate.collectAsState()

    BackHandler {
        app.hideSheet()
    }

    Content(
        initialStartDate = startDate,
        initialEndDate = endDate,
        onClearClick = {
            activityListVM.clearDateRange()
        },
        onApplyClick = { start, end ->
            activityListVM.setDateRange(
                startDate = start,
                endDate = end,
            )
            app.hideSheet()
        },
    )
}

@Suppress("MaxLineLength", "CyclomaticComplexMethod")
@Composable
private fun Content(
    initialStartDate: Long? = null,
    initialEndDate: Long? = null,
    onClearClick: () -> Unit = {},
    onApplyClick: (Long?, Long?) -> Unit = { _, _ -> },
) {
    var displayedMonth by remember {
        mutableStateOf(
            initialStartDate?.let {
                Instant.fromEpochMilliseconds(it)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
            } ?: now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        )
    }

    var startDate by remember { mutableStateOf(initialStartDate) }
    var endDate by remember { mutableStateOf(initialEndDate) }

    val hasSelection = startDate != null

    val calendar = remember { Calendar.getInstance() }

    // Swipe animation state
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(INITIAL_OFFSET) }
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(INITIAL_OFFSET) }
    val swipeThreshold = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }

    // Animation for month transition
    var isAnimating by remember { mutableStateOf(false) }
    val monthAlpha = remember { Animatable(INITIAL_ALPHA) }

    fun navigateMonth(direction: Int) {
        if (isAnimating) return

        scope.launch {
            isAnimating = true

            // Fade out and slide
            launch {
                monthAlpha.animateTo(
                    targetValue = TRANSPARENT_ALPHA,
                    animationSpec = tween(durationMillis = FADE_DURATION_MILLIS)
                )
            }

            launch {
                offsetX.animateTo(
                    targetValue = if (direction > 0) -SLIDE_OFFSET_PX else SLIDE_OFFSET_PX,
                    animationSpec = tween(durationMillis = FADE_DURATION_MILLIS)
                )
            }

            // Wait for animation to complete
            offsetX.snapTo(if (direction > 0) SLIDE_OFFSET_PX else -SLIDE_OFFSET_PX)

            // Update month
            displayedMonth = if (direction > 0) {
                displayedMonth.plusMonths(NEXT_MONTH)
            } else {
                displayedMonth.minusMonths(NEXT_MONTH)
            }

            // Fade in and slide back
            launch {
                monthAlpha.animateTo(
                    targetValue = INITIAL_ALPHA,
                    animationSpec = tween(durationMillis = FADE_DURATION_MILLIS)
                )
            }

            launch {
                offsetX.animateTo(
                    targetValue = INITIAL_OFFSET,
                    animationSpec = tween(durationMillis = FADE_DURATION_MILLIS)
                )
            }

            isAnimating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.CALENDAR)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.wallet__filter_title))

        VerticalSpacer(10.dp)

        // Month navigation header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BodyMSB(
                text = displayedMonth.toMonthYearString(),
                color = Color.White
            )

            Row {
                IconButton(
                    onClick = {
                        navigateMonth(PREVIOUS_MONTH)
                    },
                    modifier = Modifier.testTag("PrevMonth")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = Colors.Brand
                    )
                }

                IconButton(
                    onClick = {
                        navigateMonth(NEXT_MONTH)
                    },
                    modifier = Modifier.testTag("NextMonth")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = Colors.Brand
                    )
                }
            }
        }

        // Weekday headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            calendar.firstDayOfWeek = Calendar.SUNDAY
            val weekdaySymbols = SimpleDateFormat(DatePattern.WEEKDAY_FORMAT, Locale.getDefault())

            for (i in 0 until CalendarConstants.DAYS_IN_WEEK) {
                val dayOfWeek =
                    (calendar.firstDayOfWeek + i - CalendarConstants.CALENDAR_WEEK_OFFSET) % CalendarConstants.DAYS_IN_WEEK_MOD + CalendarConstants.CALENDAR_WEEK_OFFSET
                calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
                Caption13Up(
                    text = weekdaySymbols.format(calendar.time).take(CalendarConstants.WEEKDAY_ABBREVIATION_LENGTH)
                        .uppercase(),
                    color = Colors.White64,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }

        VerticalSpacer(8.dp)

        // Calendar grid with swipe gesture
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragOffset = INITIAL_OFFSET },
                        onDragEnd = {
                            scope.launch {
                                if (abs(dragOffset) > swipeThreshold) {
                                    // Trigger month change
                                    val direction =
                                        if (dragOffset < 0) NEXT_MONTH else PREVIOUS_MONTH
                                    navigateMonth(direction)
                                } else {
                                    // Spring back to original position
                                    offsetX.animateTo(
                                        targetValue = INITIAL_OFFSET,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    )
                                }
                                dragOffset = INITIAL_OFFSET
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = INITIAL_OFFSET,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                                dragOffset = INITIAL_OFFSET
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!isAnimating) {
                                dragOffset += dragAmount
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount)
                                }
                            }
                        }
                    )
                }
        ) {
            CalendarGrid(
                displayedMonth = displayedMonth,
                startDate = startDate,
                endDate = endDate,
                onSelectDate = { selectedDate ->
                    val selectedMillis = selectedDate
                        .atStartOfDayIn(TimeZone.currentSystemDefault())
                        .toEpochMilliseconds()

                    // Check if startDate and endDate are on the same day
                    val isSameDay = startDate != null && endDate != null &&
                        Instant.fromEpochMilliseconds(startDate!!)
                            .toLocalDateTime(TimeZone.currentSystemDefault()).date ==
                        Instant.fromEpochMilliseconds(endDate!!)
                            .toLocalDateTime(TimeZone.currentSystemDefault()).date

                    when {
                        startDate == null -> {
                            // First selection - set both start (beginning of day) and end (end of day)
                            startDate = selectedMillis
                            endDate = selectedDate.endOfDay()
                        }

                        isSameDay -> {
                            // Second selection - create range
                            if (selectedMillis < startDate!!) {
                                // Selected date is before start, swap them
                                val originalStart = Instant.fromEpochMilliseconds(startDate!!)
                                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
                                endDate = originalStart.endOfDay()
                                startDate = selectedMillis
                            } else if (selectedMillis == startDate) {
                                // Same date clicked - do nothing
                                return@CalendarGrid
                            } else {
                                // Selected date is after start, set as end of day
                                endDate = selectedDate.endOfDay()
                            }
                        }

                        else -> {
                            // Third selection - start new range
                            startDate = selectedMillis
                            endDate = selectedDate.endOfDay()
                        }
                    }
                },
                modifier = Modifier
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .graphicsLayer {
                        alpha = monthAlpha.value
                    }
            )
        }

        FillHeight()

        // Display selected range
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            if (startDate != null) {
                val startLocalDate = Instant.fromEpochMilliseconds(startDate!!)
                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
                val endLocalDate = endDate?.let {
                    Instant.fromEpochMilliseconds(it)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                }

                BodyMSB(
                    text = if (endLocalDate != null && startLocalDate != endLocalDate) {
                        "${startLocalDate.toFormattedString()} - ${endLocalDate.toFormattedString()}"
                    } else {
                        startLocalDate.toFormattedString()
                    },
                    color = Color.White
                )
            }
        }

        VerticalSpacer(36.dp)

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            SecondaryButton(
                onClick = {
                    startDate = null
                    endDate = null
                    onClearClick()
                },
                text = stringResource(R.string.wallet__filter_clear),
                enabled = hasSelection,
                modifier = Modifier
                    .weight(1f)
                    .testTag("CalendarClearButton")
            )
            PrimaryButton(
                onClick = {
                    onApplyClick(startDate, endDate)
                },
                text = stringResource(R.string.wallet__filter_apply),
                enabled = hasSelection,
                modifier = Modifier
                    .weight(1f)
                    .testTag("CalendarApplyButton")
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    displayedMonth: LocalDate,
    startDate: Long?,
    endDate: Long?,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val daysInMonth = remember(displayedMonth) {
        getDaysInMonth(displayedMonth)
    }

    val today = remember {
        now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        daysInMonth.chunked(CalendarConstants.DAYS_IN_WEEK).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                week.forEach { date ->
                    if (date != null) {
                        val dateMillis = date.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                        val isSelected = isDateInRange(dateMillis, startDate, endDate)
                        val isStartDate = dateMillis == startDate
                        val isEndDate = date.endOfDay() == endDate
                        val isToday = date == today

                        CalendarDayView(
                            date = date,
                            isSelected = isSelected,
                            isStartDate = isStartDate,
                            isEndDate = isEndDate,
                            isToday = isToday,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // Empty space for days outside the month
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayView(
    date: LocalDate,
    isSelected: Boolean,
    isStartDate: Boolean,
    isEndDate: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(DAY_ASPECT_RATIO)
            .clickableAlpha(onClick = onClick)
            .testTag(if (isToday) "Today" else "Day-${date.day}")
    ) {
        // Background for range selection
        if (isSelected) {
            when {
                isStartDate && isEndDate -> {
                    // Single day or same start/end
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .background(Colors.Brand16)
                    )
                }

                isStartDate -> {
                    // Start of range
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(
                                RoundedCornerShape(
                                    topStartPercent = ROUNDED_CORNER_PERCENT,
                                    bottomStartPercent = ROUNDED_CORNER_PERCENT,
                                    topEndPercent = NO_CORNER_RADIUS,
                                    bottomEndPercent = NO_CORNER_RADIUS,
                                )
                            )
                            .background(Colors.Brand16)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .background(Colors.Brand16)
                    )
                }

                isEndDate -> {
                    // End of range
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(
                                RoundedCornerShape(
                                    topStartPercent = NO_CORNER_RADIUS,
                                    bottomStartPercent = NO_CORNER_RADIUS,
                                    topEndPercent = ROUNDED_CORNER_PERCENT,
                                    bottomEndPercent = ROUNDED_CORNER_PERCENT,
                                )
                            )
                            .background(Colors.Brand16)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .background(Colors.Brand16)
                    )
                }

                else -> {
                    // Middle of range
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Colors.Brand16)
                    )
                }
            }
        }

        // Today indicator (if not selected)
        if (isToday && !isSelected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = TODAY_INDICATOR_ALPHA))
            )
        }

        // Day number
        BodyMSB(
            text = date.day.toString(),
            color = if (isStartDate || isEndDate) Colors.Brand else Color.White
        )
    }
}

private fun LocalDate.toFormattedString(): String {
    val formatter = SimpleDateFormat(DatePattern.DATE_FORMAT, Locale.getDefault())
    val calendar = Calendar.getInstance()
    calendar.set(year, month.number - CalendarConstants.MONTH_INDEX_OFFSET, day)
    return formatter.format(calendar.time)
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        BottomSheetPreview {
            Content()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithSelection() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                initialStartDate = now()
                    .minus(CalendarConstants.DAYS_IN_WEEK.days)
                    .toEpochMilliseconds(),
                initialEndDate = now().toEpochMilliseconds(),
            )
        }
    }
}
