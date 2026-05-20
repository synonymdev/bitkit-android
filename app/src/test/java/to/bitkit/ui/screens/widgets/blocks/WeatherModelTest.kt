package to.bitkit.ui.screens.widgets.blocks

import org.junit.Test
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.WeatherDTO
import java.util.Locale
import kotlin.test.assertEquals

class WeatherModelTest {

    @Test
    fun `toWeatherModel uses current fee override`() {
        val weather = WeatherDTO(
            condition = FeeCondition.GOOD,
            currentFee = "$ 0.52",
            nextBlockFee = 6,
            avgFeeSats = 520L,
        )

        val model = weather.toWeatherModel(
            currentFee = "€ 0.48",
            locale = Locale.US,
        )

        assertEquals("€ 0.48", model.currentFee)
    }
}
