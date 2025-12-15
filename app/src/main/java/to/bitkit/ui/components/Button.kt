package to.bitkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.modifiers.alphaFeedback
import to.bitkit.ui.shared.util.primaryButtonStyle
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
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
    color: Color? = null,
) {
    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    val buttonShape = MaterialTheme.shapes.large

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.primaryColors.copy(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = contentPadding,
        shape = buttonShape,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
            .primaryButtonStyle(
                isEnabled = enabled && !isLoading,
                shape = buttonShape,
                primaryColor = color
            )
            .alphaFeedback(enabled = enabled && !isLoading)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier = if (enabled) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                colorFilter = ColorFilter.tint(Colors.White32)
                            }
                        }
                    ) {
                        icon()
                    }
                }
                text?.let {
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    val border = BorderStroke(2.dp, if (enabled) Colors.Gray4 else Color.Transparent)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.secondaryColors,
        contentPadding = contentPadding,
        border = border,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier = if (enabled) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                colorFilter = ColorFilter.tint(Colors.White32)
                            }
                        }
                    ) {
                        icon()
                    }
                }
                text?.let {
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun TertiaryButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    TextButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = AppButtonDefaults.tertiaryColors,
        contentPadding = contentPadding,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Colors.White32,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size.height / 2)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier = if (enabled) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                colorFilter = ColorFilter.tint(Colors.White32)
                            }
                        }
                    ) {
                        icon()
                    }
                }
                text?.let {
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                text = "Primary with padding",
                modifier = Modifier.padding(horizontal = 32.dp),
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
                fullWidth = false,
                size = ButtonSize.Small,
                onClick = {},
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryButton(
                    text = "Primary Small",
                    fullWidth = false,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
                PrimaryButton(
                    text = "Primary Small",
                    fullWidth = false,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
            }
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
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                PrimaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
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
                text = "Secondary With padding",
                modifier = Modifier.padding(horizontal = 32.dp),
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
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            SecondaryButton(
                text = "Secondary Small",
                size = ButtonSize.Small,
                fullWidth = false,
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
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                SecondaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
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
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        tint = Colors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small",
                size = ButtonSize.Small,
                fullWidth = false,
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
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TertiaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                TertiaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
        }
    }
}
