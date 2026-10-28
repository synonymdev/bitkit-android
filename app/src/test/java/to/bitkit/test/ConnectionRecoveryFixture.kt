package to.bitkit.test

import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.AppDb
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.data.keychain.Keychain

fun connectionRecoveryFixture(
    settings: SettingsStore,
    keychain: Keychain = mock(),
    currentBinding: () -> String = { "test-wallet-binding" },
): WalletConnectionRecovery {
    val db = mock<AppDb>()
    whenever(db.configDao()).thenReturn(mock())
    whenever(db.configDao().getAll()).thenReturn(MutableStateFlow(listOf(ConfigEntity())))
    val binding = mock<VssStoreIdProvider>()
    whenever(binding.getBackupWalletBinding(any())).thenAnswer { currentBinding() }
    return WalletConnectionRecovery(keychain, db, binding, settings)
}
