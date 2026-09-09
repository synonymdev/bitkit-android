package to.bitkit.ui.screens.subscriptions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.MoneyCell
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors

@Composable
internal fun SubscriptionRow(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    subtitle: String,
    faded: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (faded) 0.5f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickableAlpha(onClick = onClick)
            .padding(16.dp)
    ) {
        SubscriptionAvatar(subscription = subscription, contact = contact, size = 40.dp)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            BodyMSB(
                text = subscription.note ?: stringResource(R.string.subscriptions__subscription),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CaptionB(
                text = subtitle,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MoneyCell(sats = subscription.displaySats)
    }
}

@Composable
internal fun SubscriptionDefaultIcon(size: Dp) {
    Image(
        painter = painterResource(R.drawable.subscription_default_icon),
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5))
            .background(Colors.White).padding(size / 8)
    )
}

@Composable
internal fun SubscriptionAvatar(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    size: Dp,
) {
    val iconUri = subscription.metadata.iconUri
    if (iconUri == null && subscription.isCreatedByUser) {
        SubscriptionDefaultIcon(size = size)
    } else if (iconUri == null) {
        PubkyContactAvatar(profile = contact, size = size)
    } else {
        PubkyImage(uri = iconUri, size = size, shape = RoundedCornerShape(size / 5))
    }
}
