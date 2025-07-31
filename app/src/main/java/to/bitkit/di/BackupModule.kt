package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.data.backup.BackupClient
import to.bitkit.data.backup.BackupClientRust

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    fun provideBackupClient(client: BackupClientRust) : BackupClient {
        return client
    }
}
