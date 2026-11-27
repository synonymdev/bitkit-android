package to.bitkit.wear.ui.components

import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.tooling.preview.devices.WearDevices

/** Previews on the two round watch sizes the app is laid out for. */
@Preview(device = WearDevices.LARGE_ROUND, backgroundColor = 0xff000000, showBackground = true, showSystemUi = true)
@Preview(device = WearDevices.SMALL_ROUND, backgroundColor = 0xff000000, showBackground = true, showSystemUi = true)
annotation class WearPreviewDevices
