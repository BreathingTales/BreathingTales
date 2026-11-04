package com.breathingtales.di

import com.breathingtales.data.voice.AudioInput
import com.breathingtales.data.voice.MicCapture
import com.breathingtales.data.voice.MicVoiceInput
import com.breathingtales.data.voice.PleosTtsSpeech
import com.breathingtales.domain.port.SpeechPort
import com.breathingtales.domain.port.VoiceInputPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 음성 (08). 에뮬레이터에서도 Pleos TTS·STT가 동작하므로 Fake로 바꾸지 않는다 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    abstract fun speech(impl: PleosTtsSpeech): SpeechPort

    @Binds
    abstract fun voiceInput(impl: MicVoiceInput): VoiceInputPort

    /** 참여 듣기의 소리 공급원. 테스트는 녹음 파일을 넣는다 */
    @Binds
    abstract fun audioInput(impl: MicCapture): AudioInput
}
