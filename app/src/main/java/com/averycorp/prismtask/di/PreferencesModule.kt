package com.averycorp.prismtask.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.sortDataStore: DataStore<Preferences> by preferencesDataStore(name = "sort_prefs")
internal val Context.calendarSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "gcal_sync_prefs")
internal val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")
internal val Context.ndPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nd_prefs")

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides
    @Singleton
    fun provideSortPreferences(
        @ApplicationContext context: Context
    ): SortPreferences =
        SortPreferences(context.sortDataStore)

    @Provides
    @Singleton
    fun provideCalendarSyncPreferences(
        @ApplicationContext context: Context
    ): CalendarSyncPreferences =
        CalendarSyncPreferences(context.calendarSyncDataStore)

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context
    ): UserPreferencesDataStore =
        UserPreferencesDataStore(context.userPrefsDataStore)

    @Provides
    @Singleton
    fun provideNdPreferencesDataStore(
        @ApplicationContext context: Context
    ): NdPreferencesDataStore =
        NdPreferencesDataStore(context.ndPrefsDataStore)

    @Provides
    @Singleton
    fun provideNotificationPreferences(
        @ApplicationContext context: Context
    ): NotificationPreferences =
        NotificationPreferences.from(context)
}
