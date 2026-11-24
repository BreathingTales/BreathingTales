package com.breathingtales.di

import com.breathingtales.data.settings.PrefsRestoreJournal
import com.breathingtales.data.settings.PrefsSettingsRepository
import com.breathingtales.domain.port.RestoreJournalPort
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

    /** 연출 중 앱이 죽어도 다음 실행에서 실내를 되돌리도록 SafetyGuard가 남기는 기록 */
    @Binds
    abstract fun restoreJournal(impl: PrefsRestoreJournal): RestoreJournalPort
}
