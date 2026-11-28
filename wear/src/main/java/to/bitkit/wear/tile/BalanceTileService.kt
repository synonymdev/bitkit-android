package to.bitkit.wear.tile

import android.content.ComponentName
import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconEdgeButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.wear.R
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.ui.LaunchRequest
import to.bitkit.wear.ui.MainActivity
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.theme.Colors
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * The Bitkit tile: the balance the app loaded in this process, and an edge button that opens the receive QR. Without
 * a wallet it shows the Bitkit mark and a button that opens the app. It only reads the wallet's state in memory and
 * never starts the wallet, so until the app loaded the balance the dimmed mark stands in for it.
 */
@AndroidEntryPoint
class BalanceTileService : TileService() {
    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val ID_QR = "qr"
        private const val ID_LOGO = "logo"
        private const val ID_LOGO_DIM = "logo_dim"
        private const val ID_RECEIVE = "receive"
        private const val ID_OPEN = "open"

        /** How long a drawn tile stays valid without an update request, so a balance this process lost goes away. */
        private val FRESHNESS = 15.minutes

        /** The tile logo's height over its width. */
        private const val LOGO_ASPECT = 82f / 279f

        /** The share of the screen's width the Bitkit mark takes without a wallet, a little less than onboarding's. */
        private const val LOGO_WIDTH_SIGNED_OUT = 0.6f

        /** The share of the screen's width the mark takes where it stands in for a hidden balance, as on Balance. */
        private const val LOGO_WIDTH_HIDDEN = 0.4f
    }

    @Inject
    lateinit var walletRepo: WalletRepo

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val state = tileState(walletRepo.hasWallet(), walletRepo.walletState.value)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS.inWholeMilliseconds)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration, state))
                )
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(ID_QR, image(R.drawable.ic_qr_code))
            .addIdToImageMapping(ID_LOGO, image(R.drawable.tile_logo))
            .addIdToImageMapping(ID_LOGO_DIM, image(R.drawable.tile_logo_dim))
            .build()
    }

    private fun layout(device: DeviceParameters, state: TileState) = materialScope(
        context = this,
        deviceConfiguration = device,
        allowDynamicTheme = false,
        defaultColorScheme = ColorScheme(
            primary = Colors.Brand.toArgb().argb,
            onPrimary = Colors.Black.toArgb().argb,
            onSurface = Colors.White.toArgb().argb,
        ),
    ) {
        when (state) {
            TileState.SignedOut -> primaryLayout(
                mainSlot = { logo(device, ID_LOGO, LOGO_WIDTH_SIGNED_OUT) },
                bottomSlot = {
                    textEdgeButton(
                        onClick = clickable(launchApp(openReceive = false), ID_OPEN),
                        labelContent = { text(getString(R.string.onboarding__restore).layoutString) },
                    )
                },
            )
            is TileState.Wallet -> primaryLayout(
                mainSlot = { state.balance?.let { balance(it) } ?: logo(device, ID_LOGO_DIM, LOGO_WIDTH_HIDDEN) },
                bottomSlot = {
                    iconEdgeButton(
                        onClick = clickable(launchApp(openReceive = true), ID_RECEIVE),
                        modifier = LayoutModifier.contentDescription(getString(R.string.receive__title)),
                        iconContent = { icon(ID_QR) },
                    )
                },
            )
        }
    }

    private fun MaterialScope.balance(amount: DisplayAmount) = text(
        text = "${amount.symbol} ${amount.text}".layoutString,
        typography = Typography.DISPLAY_SMALL,
        color = Colors.White.toArgb().argb,
        maxLines = 1,
    )

    private fun logo(device: DeviceParameters, id: String, widthShare: Float): LayoutElementBuilders.Image {
        val width = device.screenWidthDp * widthShare
        return LayoutElementBuilders.Image.Builder()
            .setResourceId(id)
            .setWidth(dp(width))
            .setHeight(dp(width * LOGO_ASPECT))
            .build()
    }

    private fun launchApp(openReceive: Boolean): ActionBuilders.LaunchAction {
        val activity = ComponentName(this, MainActivity::class.java)
        val extras = if (openReceive) {
            mapOf(LaunchRequest.EXTRA_OPEN to ActionBuilders.stringExtra(LaunchRequest.OPEN_RECEIVE))
        } else {
            emptyMap()
        }
        return ActionBuilders.launchAction(activity, extras)
    }

    private fun image(resId: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build())
        .build()
}
