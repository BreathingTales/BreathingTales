package com.breathingtales.di

import com.breathingtales.data.story.AssetStoryRepository
import com.breathingtales.domain.repository.StoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StoryModule {
    @Binds
    abstract fun storyRepository(impl: AssetStoryRepository): StoryRepository
}
