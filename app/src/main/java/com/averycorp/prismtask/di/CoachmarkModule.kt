package com.averycorp.prismtask.di

import com.averycorp.prismtask.data.preferences.TourCardPreferences
import com.averycorp.prismtask.ui.coachmark.CoachmarkController
import com.averycorp.prismtask.ui.coachmark.DEFAULT_COACHMARK_TOUR
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoachmarkModule {
    /**
     * Production [CoachmarkController]. The tour content is the static
     * [DEFAULT_COACHMARK_TOUR] list and the scope is a Main-immediate
     * supervisor — survives configuration changes via Hilt's
     * [Singleton] scope.
     *
     * Tests construct the controller directly with custom params (see
     * [com.averycorp.prismtask.ui.coachmark.CoachmarkControllerTest]).
     */
    @Provides
    @Singleton
    fun provideCoachmarkController(
        tourCardPreferences: TourCardPreferences
    ): CoachmarkController = CoachmarkController(
        tourCardPreferences = tourCardPreferences,
        tour = DEFAULT_COACHMARK_TOUR,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    )
}
