package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.theme.Colors

@Composable
fun PubkyContactAvatar(
    profile: PubkyProfile,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    testTag: String = "PubkyContactAvatar",
) {
    if (profile.imageUrl != null) {
        PubkyImage(
            uri = profile.imageUrl,
            size = size,
            modifier = modifier.testTag(testTag),
        )
        return
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Colors.White10)
            .testTag(testTag)
    ) {
        BodySSB(
            text = profile.name.firstOrNull()?.uppercase().orEmpty(),
            color = Colors.White,
        )
    }
}
