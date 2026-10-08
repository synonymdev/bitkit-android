package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.theme.Colors

@Composable
internal fun PaymentRequestSummary(
    profile: PubkyProfile?,
    note: String?,
    modifier: Modifier = Modifier,
) {
    if (profile == null) return
    val noteColor = if (note != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.height(IntrinsicSize.Min)
    ) {
        SendCell(
            caption = stringResource(R.string.wallet__send_from),
            modifier = Modifier.weight(1f)
        ) {
            PaymentRequestSummaryValue(
                text = profile.name,
                icon = R.drawable.ic_user,
                testTag = "PaymentRequestFrom",
            )
        }
        SendCell(
            caption = stringResource(R.string.wallet__payment_request_for),
            modifier = Modifier.weight(1f)
        ) {
            PaymentRequestSummaryValue(
                text = note ?: stringResource(R.string.wallet__payment_request_not_specified),
                textColor = noteColor,
                icon = R.drawable.ic_note,
                testTag = "PaymentRequestFor",
            )
        }
    }
}

@Composable
private fun PaymentRequestSummaryValue(
    text: String,
    @DrawableRes icon: Int,
    testTag: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Colors.White,
            modifier = Modifier.size(16.dp)
        )
        BodySSB(
            text = text,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
internal fun PaymentRequestInvoiceNote(
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Caption13Up(text = stringResource(R.string.wallet__activity_invoice_note), color = Colors.White64)
        VerticalSpacer(8.dp)
        ZigzagDivider()
        Title(
            text = note,
            color = Colors.White,
            modifier = Modifier
                .fillMaxWidth()
                .background(Colors.White10)
                .padding(24.dp)
                .testTag("PaymentRequestInvoiceNote")
        )
    }
}
