package to.bitkit.data.keychain

import to.bitkit.utils.AppError

internal fun Keychain.hasPendingWalletRestore(): Boolean =
    exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name) || exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name)

internal fun Keychain.requireWalletRestoreComplete() {
    if (hasPendingWalletRestore()) throw WalletBackupRestorePendingError()
}

class WalletBackupRestorePendingError : AppError(
    "Wallet backup restoration is incomplete. Retry the restore before continuing."
)
