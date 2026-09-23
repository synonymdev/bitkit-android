package to.bitkit.services.offline

import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.PreparedOfflineInvoice
import to.bitkit.services.UnavailableOfflineReceiveService
import javax.inject.Inject
import javax.inject.Qualifier

/** Marks the native provider contributed only when the app is compiled with `ldkNodeLocalVersion`. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class NativeOfflineReceive

/**
 * Production binding of [OfflineReceiveService]. It delegates to the native provider only when one was compiled in
 * and the "Offline receive (experimental)" dev toggle is on; otherwise it behaves as
 * [UnavailableOfflineReceiveService].
 */
class GatedOfflineReceiveService @Inject constructor(
    @NativeOfflineReceive private val nativeServices: Set<@JvmSuppressWildcards OfflineReceiveService>,
    private val unavailable: UnavailableOfflineReceiveService,
    private val settingsSource: OfflineReceiveSettingsSource,
) : OfflineReceiveService {

    private suspend fun delegate(): OfflineReceiveService {
        val native = nativeServices.singleOrNull() ?: return unavailable
        return if (settingsSource.current().isEnabled) native else unavailable
    }

    override suspend fun canReceive(amountSats: ULong): Result<Boolean> = delegate().canReceive(amountSats)

    override suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> =
        delegate().prepareInvoice(request)
}
