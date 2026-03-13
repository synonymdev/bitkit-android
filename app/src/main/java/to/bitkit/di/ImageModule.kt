package to.bitkit.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import to.bitkit.data.PubkyFetcher
import to.bitkit.services.PubkyService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        pubkyService: PubkyService,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(PubkyFetcher.Factory(pubkyService)) }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("pubky-images"))
                .build()
        }
        .build()
}
