package com.breathingtales.domain.repository

import com.breathingtales.domain.model.ParentSettings
import kotlinx.coroutines.flow.StateFlow

/** 부모 설정 저장소 (09) */
interface SettingsRepository {
    val settings: StateFlow<ParentSettings>

    fun update(transform: (ParentSettings) -> ParentSettings)
}
