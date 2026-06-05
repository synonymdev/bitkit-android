package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import to.bitkit.appwidget.AndroidAppWidgetActiveWidgets
import to.bitkit.appwidget.AndroidAppWidgetAlarmClient
import to.bitkit.appwidget.AndroidAppWidgetWorkClient
import to.bitkit.appwidget.AndroidElapsedRealtimeProvider
import to.bitkit.appwidget.AppWidgetActiveWidgets
import to.bitkit.appwidget.AppWidgetAlarmClient
import to.bitkit.appwidget.AppWidgetWorkClient
import to.bitkit.appwidget.ElapsedRealtimeProvider

@Module
@InstallIn(SingletonComponent::class)
interface AppWidgetRefreshSchedulerModule {
    @Binds
    fun bindActiveWidgets(impl: AndroidAppWidgetActiveWidgets): AppWidgetActiveWidgets

    @Binds
    fun bindWorkClient(impl: AndroidAppWidgetWorkClient): AppWidgetWorkClient

    @Binds
    fun bindAlarmClient(impl: AndroidAppWidgetAlarmClient): AppWidgetAlarmClient

    @Binds
    fun bindElapsedRealtimeProvider(impl: AndroidElapsedRealtimeProvider): ElapsedRealtimeProvider
}
