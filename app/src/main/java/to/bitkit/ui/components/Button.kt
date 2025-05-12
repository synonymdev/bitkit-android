package to.bitkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppButtonDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

enum class ButtonSize {
    Small, Large;

    val height: Dp
        get() = when (this) {
            Small -> 40.dp
            Large -> 56.dp
        }
    val horizontalPadding: Dp
        get() = when (this) {
            Small -> 16.dp
            Large -> 24.dp
        }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
    color: Color = Colors.White16,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.primaryColors.copy(containerColor = color),
        contentPadding = PaddingValues(horizontal = size.horizontalPadding),
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(size.height)
            .then(modifier)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            if (icon != null) {
                Box(modifier = if (enabled) Modifier else Modifier.alpha(0.5f)) {
                    icon()
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.secondaryColors,
        contentPadding = PaddingValues(horizontal = size.horizontalPadding),
        border = BorderStroke(2.dp, if (enabled) Colors.White16 else Color.Transparent),
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(size.height)
            .then(modifier)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.tertiaryColors,
        contentPadding = PaddingValues(horizontal = size.horizontalPadding),
        modifier = Modifier
            .fillMaxWidth()
            .height(size.height)
            .then(modifier)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun PrimaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            PrimaryButton(
                text = "Primary",
                onClick = {},
            )
            PrimaryButton(
                text = "Primary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            PrimaryButton(
                text = "Primary Loading",
                onClick = {},
                isLoading = true,
            )
            PrimaryButton(
                text = "Primary Disabled",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                enabled = false,
            )
            PrimaryButton(
                text = "Primary Small",
                size = ButtonSize.Small,
                onClick = {},
            )
            PrimaryButton(
                text = "Primary Small Color Not Full",
                size = ButtonSize.Small,
                onClick = {},
                fullWidth = false,
                color = Colors.Brand,
            )
            PrimaryButton(
                text = "Primary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {},
            )
            PrimaryButton(
                text = "Primary Small Disabled",
                size = ButtonSize.Small,
                onClick = {},
                enabled = false,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SecondaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            SecondaryButton(
                text = "Secondary",
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            SecondaryButton(
                text = "Secondary Loading",
                isLoading = true,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Disabled",
                onClick = {},
                enabled = false,
            )
            SecondaryButton(
                text = "Secondary Small",
                size = ButtonSize.Small,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Small Disabled",
                size = ButtonSize.Small,
                onClick = {},
                enabled = false,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TertiaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            TertiaryButton(
                text = "Tertiary",
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            TertiaryButton(
                text = "Tertiary Loading",
                isLoading = true,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Disabled",
                enabled = false,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small",
                size = ButtonSize.Small,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small Disabled",
                size = ButtonSize.Small,
                enabled = false,
                onClick = {}
            )
        }
    }
}
