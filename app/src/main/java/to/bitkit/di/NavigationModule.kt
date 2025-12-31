package to.bitkit.di

import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NavigationModule {
    @Singleton
    @Provides
    fun provideNavigator(): Navigator {
        val backStack = mutableStateListOf<NavKey>(Routes.Home)
        return Navigator(backStack)
    }
}
