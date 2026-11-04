package com.breathingtales.viewmodel

import com.breathingtales.BuildConfig

import androidx.lifecycle.ViewModel
import com.breathingtales.domain.model.BlowSensitivity
import com.breathingtales.domain.model.ParentSettings
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.safety.Intensity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ParentSettings> = repository.settings


    fun setChildName(name: String) = repository.update { it.copy(childName = name.trim()) }

    fun setIntensity(intensity: Intensity) = repository.update { it.copy(intensity = intensity) }

    fun setBlowSensitivity(sensitivity: BlowSensitivity) = repository.update { it.copy(blowSensitivity = sensitivity) }

    fun setSeatMotion(enabled: Boolean) = repository.update { it.copy(seatMotion = enabled) }

    /** 시연 모드 토글은 debug 빌드에서만 보여 준다 */
    val demoAvailable: Boolean = BuildConfig.DEBUG

    fun setDemoHud(enabled: Boolean) = repository.update { it.copy(demoHud = enabled) }
}
