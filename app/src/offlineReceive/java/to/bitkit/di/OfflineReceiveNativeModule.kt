package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import to.bitkit.services.NodeBuilderCustomizer
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.offline.LdkOfflineReceiveService
import to.bitkit.services.offline.NativeOfflineReceive
import to.bitkit.services.offline.OfflineReceiveClientProvider
import to.bitkit.services.offline.ldk.LdkOfflineReceiveClientProvider
import to.bitkit.services.offline.ldk.LdkOfflineReceiveNodeCustomizer

/**
 * Compiled only with `ldkNodeLocalVersion`. Contributes the native offline receive provider, which
 * [to.bitkit.services.offline.GatedOfflineReceiveService] uses only while the dev toggle is on.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineReceiveNativeModule {

    @Binds
    abstract fun bindOfflineReceiveClientProvider(
        provider: LdkOfflineReceiveClientProvider,
    ): OfflineReceiveClientProvider

    @Binds
    @IntoSet
    abstract fun bindOfflineReceiveNodeCustomizer(customizer: LdkOfflineReceiveNodeCustomizer): NodeBuilderCustomizer

    @Binds
    @IntoSet
    @NativeOfflineReceive
    abstract fun bindNativeOfflineReceiveService(service: LdkOfflineReceiveService): OfflineReceiveService
}
