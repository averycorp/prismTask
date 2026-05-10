package com.averycorp.prismtask.di

import com.averycorp.prismtask.domain.rating.RatingClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RatingModule {
    @Provides
    @Singleton
    fun provideRatingClock(): RatingClock = RatingClock { System.currentTimeMillis() }
}
