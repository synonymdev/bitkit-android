package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PrimaryNode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrimaryNodeRepo @Inject constructor(
    settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    val primaryNodeUri = settingsStore.data.map { it.primaryNodeUri }.distinctUntilChanged()
    val fundingPaused = settingsStore.data.map { it.primaryFundingPaused }.distinctUntilChanged()

    suspend fun configure(input: String): Result<PrimaryNode> = withContext(ioDispatcher) {
        runSuspendCatching {
            lightningRepo.configurePrimaryNode(PrimaryNode.parse(input)).getOrThrow()
        }
    }
}
