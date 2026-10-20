package to.bitkit.utils.timedsheets

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.UsdtPreference
import to.bitkit.data.serializers.SettingsSerializer
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.timedsheets.sheets.UsdtTimedSheet
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsdtTimedSheetTest : BaseUnitTest() {
    @Test
    fun `old settings retry an unseen intro but preserve seen and explicit choices`() = test {
        val settings = MutableStateFlow(SettingsSerializer.readFrom("{}".byteInputStream()))
        val store = mock<SettingsStore> { on { data } doReturn settings }
        val sheet = UsdtTimedSheet(store)
        assertTrue(sheet.shouldShow())
        sheet.onShown()
        assertTrue(sheet.shouldShow())

        settings.value = SettingsData(hasSeenUsdtIntro = true)
        val persisted = json.encodeToString(settings.value)
        settings.value = SettingsSerializer.readFrom(persisted.byteInputStream())
        assertFalse(UsdtTimedSheet(store).shouldShow())

        for (preference in listOf(UsdtPreference.ENABLED, UsdtPreference.DISABLED)) {
            settings.value = SettingsData(usdtPreference = preference)
            assertFalse(UsdtTimedSheet(store).shouldShow())
        }
    }
}
