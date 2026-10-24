package to.bitkit.models

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Date on which Blocktank closes the remaining Lightning channels; set before release. */
val CHANNELS_CLOSE_DATE: LocalDate = LocalDate.of(2026, 12, 15)

/** Third-party Lightning service providers whose sites sell a channel the user claims by scanning a QR. */
val THIRD_PARTY_LSPS: List<ThirdPartyLsp> = listOf(
    ThirdPartyLsp(name = "LNBIG", url = "https://lnbig.com/#/open-channel"),
    ThirdPartyLsp(name = "ZEUS Olympus", url = "https://channels.zeuslsp.com/"),
    ThirdPartyLsp(name = "Megalithic", url = "https://megalithic.me/get_a_channel"),
    ThirdPartyLsp(name = "Amboss Magma", url = "https://amboss.space/magma"),
)

data class ThirdPartyLsp(
    val name: String,
    val url: String,
)

fun formatChannelsCloseDate(locale: Locale = Locale.getDefault()): String =
    CHANNELS_CLOSE_DATE.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
