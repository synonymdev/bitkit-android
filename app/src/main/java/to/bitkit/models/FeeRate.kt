package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.synonym.bitkitcore.FeeRates
import to.bitkit.R
import to.bitkit.ui.theme.Colors

enum class FeeRate(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val shortDescription: Int,
    @DrawableRes val icon: Int,
    val color: Color,
) {
    FAST(
        title = R.string.fee__fast__title,
        description = R.string.fee__fast__description,
        shortDescription = R.string.fee__fast__shortDescription,
        color = Colors.Brand,
        icon = R.drawable.ic_speed_fast,
    ),
    NORMAL(
        title = R.string.fee__normal__title,
        description = R.string.fee__normal__description,
        shortDescription = R.string.fee__normal__shortDescription,
        color = Colors.Brand,
        icon = R.drawable.ic_speed_normal,
    ),
    SLOW(
        title = R.string.fee__slow__title,
        description = R.string.fee__slow__description,
        shortDescription = R.string.fee__slow__shortDescription,
        color = Colors.Brand,
        icon = R.drawable.ic_speed_slow,
    ),
    MINIMUM(
        title = R.string.fee__minimum__title,
        description = R.string.fee__minimum__description,
        shortDescription = R.string.fee__minimum__shortDescription,
        color = Colors.Brand,
        icon = R.drawable.ic_speed_slow,
    ),
    CUSTOM(
        title = R.string.fee__custom__title,
        description = R.string.fee__custom__description,
        shortDescription = R.string.fee__custom__shortDescription,
        color = Colors.White64,
        icon = R.drawable.ic_settings,
    );

    fun toSpeed(): TransactionSpeed {
        return when (this) {
            FAST -> TransactionSpeed.Fast
            NORMAL -> TransactionSpeed.Medium
            MINIMUM, SLOW -> TransactionSpeed.Slow
            CUSTOM -> TransactionSpeed.Custom(0u)
        }
    }

    companion object {
        fun fromSpeed(speed: TransactionSpeed): FeeRate {
            return when (speed) {
                is TransactionSpeed.Fast -> FAST
                is TransactionSpeed.Medium -> NORMAL
                is TransactionSpeed.Slow -> SLOW
                is TransactionSpeed.Custom -> CUSTOM
            }
        }

        fun fromSatsPerVByte(satsPerVByte: ULong, feeRates: FeeRates): FeeRate {
            val value = satsPerVByte.toUInt()
            return when {
                value >= feeRates.fast -> FAST
                value >= feeRates.mid -> NORMAL
                value >= feeRates.slow -> SLOW
                else -> MINIMUM
            }
        }

        @Composable
        fun getFeeDescription(
            feeRate: ULong,
            feeEstimates: FeeRates?,
        ): String {
            val feeRateEnum = feeEstimates?.let {
                fromSatsPerVByte(feeRate, it)
            } ?: NORMAL

            return stringResource(feeRateEnum.shortDescription)
        }
    }
}
