package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.scaffold.SheetTopBar

@Composable
fun SendContactTopBar(
    titleText: String,
    contact: PubkyProfile?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        SheetTopBar(
            titleText = titleText,
            onBack = onBack,
        )
        if (contact != null) {
            PubkyContactAvatar(
                profile = contact,
                size = 32.dp,
                testTag = "SendContactHeaderAvatar",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }
    }
}
