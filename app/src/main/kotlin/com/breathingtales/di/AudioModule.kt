package com.breathingtales.di

import com.breathingtales.data.audio.Media3AudioPlayer
import com.breathingtales.domain.port.AudioPlayerPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 에뮬레이터에서도 실제로 재생되므로 Fake로 바꾸지 않는다. 엔진(07) 테스트는 domain 테스트용 가짜를 쓴다 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {
    @Binds
    abstract fun audioPlayer(impl: Media3AudioPlayer): AudioPlayerPort
}
