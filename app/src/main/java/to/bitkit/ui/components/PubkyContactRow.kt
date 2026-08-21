package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors

@Composable
fun PubkyContactRow(
    profile: PubkyProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean? = null,
    isEnabled: Boolean = true,
    verticalPadding: Dp = 12.dp,
    selectionColor: Color = Colors.PubkyGreen,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickableAlpha(enabled = isEnabled, onClick = onClick)
            .then(
                if (isSelected == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        role = Role.RadioButton
                        selected = isSelected
                        if (!isEnabled) disabled()
                    }
                }
            )
            .padding(vertical = verticalPadding)
    ) {
        PubkyContactAvatar(profile = profile)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            BodyS(
                text = profile.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodySSB(
                text = profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isSelected == true) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = selectionColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
