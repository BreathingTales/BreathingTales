package com.breathingtales

import android.app.Application
import com.breathingtales.data.voice.BlowRule
import com.breathingtales.data.voice.MicVoiceInput
import com.breathingtales.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class BreathingTalesApp : Application() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var voiceInput: MicVoiceInput

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        // 부모 설정의 숨 불기 민감도를 음성 어댑터 기준 음량으로 옮긴다. 다음 듣기부터 적용된다
        appScope.launch {
            settings.settings.map { it.blowSensitivity }.distinctUntilChanged().collect {
                voiceInput.blowThresholdDb = BlowRule.thresholdFor(it)
            }
        }
    }
}
