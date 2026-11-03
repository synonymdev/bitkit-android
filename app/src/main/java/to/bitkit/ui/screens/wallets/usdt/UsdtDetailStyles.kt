package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.theme.AppTextStyles

internal object UsdtDetailStyles {
    private val lineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val Caption = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp, lineHeightStyle = lineHeight)
    val Body = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp, lineHeightStyle = lineHeight)
    val Value = Body.copy(letterSpacing = 0.4.sp)
    val Title = AppTextStyles.Title.copy(letterSpacing = 0.sp, lineHeightStyle = lineHeight)
    val SmallButton = AppTextStyles.CaptionB.copy(
        letterSpacing = 0.sp,
        lineHeight = 13.sp,
        lineHeightStyle = lineHeight
    )
    val Button = Body.copy(letterSpacing = (-0.4).sp)
    val HistoryTitle = AppTextStyles.BodyMSB.copy(letterSpacing = 0.sp, lineHeightStyle = lineHeight)
    val HistorySubtitle = AppTextStyles.CaptionB.copy(letterSpacing = 0.sp, lineHeightStyle = lineHeight)
}

@Composable
internal fun UsdtDetailField(
    caption: String,
    modifier: Modifier = Modifier,
    alignDividerToBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    SendCell(
        caption = caption,
        captionStyle = UsdtDetailStyles.Caption,
        dividerSpacing = 15.dp,
        alignDividerToBottom = alignDividerToBottom,
        content = content,
        modifier = modifier
    )
}
