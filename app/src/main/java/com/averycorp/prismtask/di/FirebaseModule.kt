package com.averycorp.prismtask.di

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides Firebase singletons. Constructor injection of [FirebaseFirestore]
 * is the prerequisite called out in PR #1118 audit § B.2 for the
 * SyncService surface-axis Strangler Fig refactor: extracted pull / push /
 * listener surfaces need to receive Firestore explicitly instead of pulling
 * `FirebaseFirestore.getInstance()` from a lazy field on the god-class.
 *
 * Other sync services (GenericPreferenceSyncService, ThemePreferencesSyncService,
 * SortPreferencesSyncService, CloudIdOrphanHealer, AccountDeletionService) keep
 * their `by lazy` pattern for now; they migrate to constructor injection
 * incrementally as Strangler Fig sub-PRs reach them.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
}
