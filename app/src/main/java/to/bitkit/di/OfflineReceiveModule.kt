package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import to.bitkit.services.NodeBuilderCustomizer
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.offline.GatedOfflineReceiveService
import to.bitkit.services.offline.NativeOfflineReceive
import to.bitkit.services.offline.OfflineReceiveRequestStore
import to.bitkit.services.offline.PreferencesOfflineReceiveRequestStore

/**
 * Offline receive wiring that compiles against the pinned ldk-node release. The native provider and the node
 * builder customizer are contributed by `src/offlineReceive` only when the app is built with `ldkNodeLocalVersion`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineReceiveModule {

    @Multibinds
    abstract fun nodeBuilderCustomizers(): Set<NodeBuilderCustomizer>

    @Multibinds
    @NativeOfflineReceive
    abstract fun nativeOfflineReceiveServices(): Set<OfflineReceiveService>

    @Binds
    abstract fun bindOfflineReceiveService(service: GatedOfflineReceiveService): OfflineReceiveService

    @Binds
    abstract fun bindOfflineReceiveRequestStore(
        store: PreferencesOfflineReceiveRequestStore,
    ): OfflineReceiveRequestStore
}
