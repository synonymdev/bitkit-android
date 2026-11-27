package to.bitkit.wear.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ext.areAnimationsDisabled
import to.bitkit.wear.ui.theme.Colors

/** The side of the viewport of `ic_broadcast`, whose path data [BroadcastPaths] holds. */
private const val VIEWPORT = 16f

/** One lighting cycle: the inner arcs light up, then the outer arcs, they hold and fade, then a pause. */
private const val CYCLE_MILLIS = 2_100

/** When the inner arcs start lighting up; the outer arcs start one stagger later. */
private const val ARC_START_MILLIS = 150

/** How far apart the inner and outer arcs start lighting up. */
private const val ARC_STAGGER_MILLIS = 400

/** How long an arc pair takes to light up. */
private const val ARC_RISE_MILLIS = 300

/** When the lit arcs start to fade. */
private const val FADE_START_MILLIS = 1_500

/** When the arcs are dark again; the rest of the cycle is the pause. */
private const val FADE_END_MILLIS = 1_800

/** The alpha of the halo behind the dot, as in the icon. */
private const val HALO_ALPHA = 0.2f

/** The alpha of an arc pair that is not lit. */
private const val DARK_ALPHA = 0.2f

/**
 * The phone's broadcast icon (`ic_broadcast`), tinted white-64 as in the Send sheet's nearby pill, telling the
 * wearer that a tap pays. The centre dot stays on while the inner arc pair, then the outer pair, brighten in a slow
 * loop; only their alpha animates. When animations are off it is the still icon. Hidden when not [visible], which
 * callers use in ambient mode and where the watch has no usable NFC.
 */
@Composable
fun NfcHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val description = stringResource(R.string.home__nfc_hint)
    val hintModifier = modifier
        .size(24.dp)
        .semantics { contentDescription = description }
        .testTag("NfcHint")
    if (LocalContext.current.areAnimationsDisabled) {
        Icon(
            painter = painterResource(R.drawable.ic_broadcast),
            contentDescription = null,
            tint = Colors.White64,
            modifier = hintModifier
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "nfcHint")
    val innerAlpha = transition.arcAlpha(startMillis = ARC_START_MILLIS, label = "nfcInnerArcs")
    val outerAlpha = transition.arcAlpha(startMillis = ARC_START_MILLIS + ARC_STAGGER_MILLIS, label = "nfcOuterArcs")
    val paths = remember { BroadcastPaths() }
    Canvas(modifier = hintModifier) {
        scale(scaleX = size.width / VIEWPORT, scaleY = size.height / VIEWPORT, pivot = Offset.Zero) {
            drawPath(paths.halo, Colors.White64, alpha = HALO_ALPHA)
            drawPath(paths.dot, Colors.White64)
            drawPath(paths.innerLeft, Colors.White64, alpha = innerAlpha.value)
            drawPath(paths.innerRight, Colors.White64, alpha = innerAlpha.value)
            drawPath(paths.outerLeft, Colors.White64, alpha = outerAlpha.value)
            drawPath(paths.outerRight, Colors.White64, alpha = outerAlpha.value)
        }
    }
}

@Composable
private fun InfiniteTransition.arcAlpha(startMillis: Int, label: String): State<Float> = animateFloat(
    initialValue = DARK_ALPHA,
    targetValue = DARK_ALPHA,
    animationSpec = infiniteRepeatable(
        keyframes {
            durationMillis = CYCLE_MILLIS
            DARK_ALPHA at startMillis
            1f at startMillis + ARC_RISE_MILLIS
            1f at FADE_START_MILLIS
            DARK_ALPHA at FADE_END_MILLIS
        }
    ),
    label = label,
)

/** The path data of `res/drawable/ic_broadcast.xml`, copied unchanged from the phone, one path per group. */
private class BroadcastPaths {
    val halo = path(
        "M8,10C9.105,10 10,9.105 10,8C10,6.895 9.105,6 8,6C6.895,6 6,6.895 6,8C6,9.105 6.895,10 " +
            "8,10Z",
    )
    val dot = path(
        "M8,6.5C7.172,6.5 6.5,7.172 6.5,8C6.5,8.828 7.172,9.5 8,9.5C8.828,9.5 9.5,8.828 " +
            "9.5,8C9.5,7.172 8.828,6.5 8,6.5ZM5.5,8C5.5,6.619 6.619,5.5 8,5.5C9.381,5.5 10.5,6.619 " +
            "10.5,8C10.5,9.381 9.381,10.5 8,10.5C6.619,10.5 5.5,9.381 5.5,8Z",
    )
    val innerLeft = path(
        "M5.172,4.463C5.367,4.659 5.367,4.975 5.171,5.17C4.8,5.541 4.505,5.982 " +
            "4.303,6.468C4.102,6.953 3.999,7.473 3.999,7.998C3.999,8.524 4.102,9.044 " +
            "4.303,9.529C4.505,10.014 4.8,10.455 5.171,10.826C5.367,11.022 5.367,11.338 " +
            "5.172,11.534C4.977,11.729 4.66,11.729 4.465,11.534C4,11.07 3.631,10.519 " +
            "3.38,9.912C3.128,9.306 2.999,8.655 2.999,7.998C2.999,7.342 3.128,6.691 " +
            "3.38,6.084C3.631,5.478 4,4.927 4.465,4.463C4.66,4.267 4.977,4.268 5.172,4.463Z",
    )
    val innerRight = path(
        "M10.828,4.463C11.023,4.268 11.34,4.267 11.535,4.463C12,4.927 12.369,5.478 " +
            "12.621,6.084C12.872,6.691 13.002,7.342 13.002,7.998C13.002,8.655 12.872,9.306 " +
            "12.621,9.912C12.369,10.519 12,11.07 11.535,11.534C11.34,11.729 11.023,11.729 " +
            "10.828,11.534C10.633,11.338 10.633,11.022 10.829,10.826C11.201,10.455 11.496,10.014 " +
            "11.697,9.529C11.898,9.044 12.002,8.524 12.002,7.998C12.002,7.473 11.898,6.953 " +
            "11.697,6.468C11.496,5.982 11.201,5.541 10.829,5.17C10.633,4.975 10.633,4.659 " +
            "10.828,4.463Z",
    )
    val outerLeft = path(
        "M3.404,2.696C3.599,2.891 3.599,3.208 3.404,3.403C2.799,4.006 2.32,4.722 " +
            "1.993,5.511C1.666,6.299 1.498,7.145 1.498,7.999C1.498,8.852 1.666,9.698 " +
            "1.993,10.486C2.32,11.275 2.799,11.991 3.404,12.594C3.599,12.79 3.599,13.106 " +
            "3.404,13.302C3.209,13.497 2.892,13.497 2.697,13.302C2,12.606 1.447,11.779 " +
            "1.069,10.869C0.692,9.959 0.498,8.984 0.498,7.999C0.498,7.013 0.692,6.038 " +
            "1.069,5.128C1.447,4.218 2,3.391 2.697,2.695C2.892,2.5 3.209,2.5 3.404,2.696Z",
    )
    val outerRight = path(
        "M12.596,2.696C12.791,2.5 13.108,2.5 13.303,2.695C14,3.391 14.553,4.218 " +
            "14.931,5.128C15.308,6.038 15.502,7.013 15.502,7.999C15.502,8.984 15.308,9.959 " +
            "14.931,10.869C14.553,11.779 14,12.606 13.303,13.302C13.108,13.497 12.791,13.497 " +
            "12.596,13.302C12.401,13.106 12.401,12.79 12.597,12.594C13.201,11.991 13.68,11.275 " +
            "14.007,10.486C14.334,9.698 14.502,8.852 14.502,7.999C14.502,7.145 14.334,6.299 " +
            "14.007,5.511C13.68,4.722 13.201,4.006 12.597,3.403C12.401,3.208 12.401,2.891 " +
            "12.596,2.696Z",
    )

    private fun path(data: String): Path = PathParser().parsePathString(data).toPath().apply {
        fillType = PathFillType.EvenOdd
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        NfcHint(visible = true)
    }
}
