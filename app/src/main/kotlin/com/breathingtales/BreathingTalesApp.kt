package com.breathingtales

import android.app.Application
import android.util.Log
import com.breathingtales.data.voice.BlowRule
import com.breathingtales.data.voice.MicVoiceInput
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.safety.SafetyGuard
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

    @Inject lateinit var guard: SafetyGuard

    private val appScope = MainScope()

    private companion object {
        const val TAG = "BTRestore"
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // 지난 실행이 연출 중에 죽었으면(강제 종료·재설치) 남긴 기록으로 실내부터 되돌린다
            guard.recoverAfterRestart().takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "지난 실행이 남긴 실내 상태를 되돌림: $it") }
            // 기어를 바꿔 멈추면 차가 등 스위치를 거부해 등이 남는다. 다시 정차하면 화면과 상관없이 되돌린다
            guard.watchPendingRestore()
        }
        // 부모 설정의 숨 불기 민감도를 음성 어댑터의 소음과의 차이로 옮긴다. 다음 듣기부터 적용된다
        appScope.launch {
            settings.settings.map { it.blowSensitivity }.distinctUntilChanged().collect {
                voiceInput.blowMarginDb = BlowRule.marginFor(it)
            }
        }
    }
}
