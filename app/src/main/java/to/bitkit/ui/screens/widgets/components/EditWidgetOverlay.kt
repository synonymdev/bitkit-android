package to.bitkit.ui.screens.widgets.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.WidgetType
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors

private val SETTINGS_DISABLED_TYPES = setOf(WidgetType.SUGGESTIONS)

/**
 * Editing chrome for a home widget cell: blurs the underlying card, lays a gray scrim and dashed
 * brand border over it, and centers the widget name with delete / settings / reorder actions.
 * Mirrors iOS `BaseWidget` editing overlay. [content] is the real card so the cell keeps the same
 * footprint in display and edit mode.
 */
@Composable
fun EditWidgetOverlay(
    type: WidgetType,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val name = stringResource(type.title)
    val settingsEnabled = type !in SETTINGS_DISABLED_TYPES

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS))
            .background(Colors.Gray6)
            .dashedBorder()
    ) {
        // The card defines the cell size; the blur, scrim and actions overlay on top of it.
        Box(modifier = Modifier.editBlur()) {
            content()
        }

        // Scrim sits above the blurred card and swallows taps so the card stays inert while editing,
        // while letting vertical drags fall through to the page scroll.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Colors.Gray6.copy(alpha = 0.8f))
                .pointerInput(Unit) {
                    detectTapGestures { /* swallow taps on the blurred card */ }
                }
        )

        EditActions(
            name = name,
            settingsEnabled = settingsEnabled,
            onDelete = onDelete,
            onSettings = onSettings,
            dragHandleModifier = dragHandleModifier,
            modifier = Modifier.matchParentSize()
        )
    }
}

private val CARD_CORNER_RADIUS = 16.dp

@Composable
private fun EditActions(
    name: String,
    settingsEnabled: Boolean,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BodyMSB(
                text = name,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("${name}_drag_and_drop_title")
            )

            VerticalSpacer(12.dp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditActionIcon(
                    iconRes = R.drawable.ic_trash,
                    contentDescription = stringResource(R.string.common__delete),
                    onClick = onDelete,
                    modifier = Modifier.testTag("${name}_WidgetActionDelete")
                )

                EditActionIcon(
                    iconRes = R.drawable.ic_settings,
                    contentDescription = stringResource(R.string.common__edit),
                    onClick = onSettings,
                    enabled = settingsEnabled,
                    modifier = Modifier.testTag("${name}_WidgetActionEdit")
                )

                EditActionIcon(
                    iconRes = R.drawable.ic_arrows_out_cardinal,
                    contentDescription = null,
                    onClick = null,
                    modifier = dragHandleModifier.testTag("${name}_WidgetActionDrag")
                )
            }
        }
    }
}

@Composable
private fun EditActionIcon(
    iconRes: Int,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(24.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
        )
    }
}

private const val DISABLED_ALPHA = 0.3f

private fun Modifier.editBlur(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(4.dp, BlurredEdgeTreatment(RoundedCornerShape(CARD_CORNER_RADIUS)))
    } else {
        this
    }

private fun Modifier.dashedBorder(): Modifier = drawWithContent {
    drawContent()
    val strokeWidth = 2.dp.toPx()
    val inset = strokeWidth / 2f
    drawRoundRect(
        color = Colors.Brand,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(CARD_CORNER_RADIUS.toPx() - inset),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        ),
    )
}
