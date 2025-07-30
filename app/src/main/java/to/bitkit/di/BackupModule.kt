package to.bitkit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientHttp

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    fun provideBackupClient(client: VssBackupClientHttp) : VssBackupClient {
        return client
    }
}
