package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun CenteredProfileHeader(
    publicKey: String,
    name: String,
    bio: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    nameTestTag: String? = null,
    notesTestTag: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text13Up(
            text = PubkyPublicKeyFormat.display(publicKey),
            color = Colors.White64,
            textAlign = TextAlign.Center,
        )

        VerticalSpacer(16.dp)

        if (imageUrl != null) {
            PubkyImage(uri = imageUrl, size = 96.dp)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Colors.Gray5)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_user_square),
                    contentDescription = null,
                    tint = Colors.White32,
                    modifier = Modifier.size(50.dp)
                )
            }
        }

        VerticalSpacer(16.dp)

        Display(
            text = name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = if (nameTestTag != null) Modifier.testTag(nameTestTag) else Modifier
        )

        if (bio.isNotEmpty()) {
            VerticalSpacer(8.dp)
            BodyM(
                text = bio,
                color = Colors.White64,
                textAlign = TextAlign.Center,
                modifier = if (notesTestTag != null) Modifier.testTag(notesTestTag) else Modifier
            )
        }
    }
}

@Preview
@Composable
private fun CenteredProfileHeaderPreview() {
    AppThemeSurface {
        CenteredProfileHeader(
            publicKey = "pk8e3qm5f4kgczagxhertyuiop1gxag",
            name = "Satoshi Nakamoto",
            bio = "Building a peer-to-peer electronic cash system.",
            imageUrl = null,
        )
    }
}
