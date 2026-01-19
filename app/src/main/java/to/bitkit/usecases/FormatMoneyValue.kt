package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.formatMoney
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FormatMoneyValue @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val locale: Locale,
) {
    suspend operator fun invoke(sats: ULong): String = withContext(bgDispatcher) {
        val unit = settingsStore.data.first().displayUnit
        sats.formatMoney(unit, locale)
    }
}
