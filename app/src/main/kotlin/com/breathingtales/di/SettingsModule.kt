package com.breathingtales.di

import com.breathingtales.data.settings.PrefsSettingsRepository
import com.breathingtales.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun settingsRepository(impl: PrefsSettingsRepository): SettingsRepository
}
