package com.breathingtales.di

import com.breathingtales.BuildConfig
import com.breathingtales.data.vehicle.EmulatorSeatState
import com.breathingtales.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 차량 연결 중 빌드 종류와 무관한 것. Fake ↔ Pleos 교체는 빌드 종류별 [VehicleBindingModule] (src/debug, src/release) */
@Module
@InstallIn(SingletonComponent::class)
object VehicleModule {
    /** 시연 모드(11): debug 빌드에서 부모 설정의 시연 모드를 켰을 때만 에뮬레이터 시트 값을 Virtual Console에서 읽는다 */
    @Provides
    @Singleton
    fun emulatorSeatState(settings: SettingsRepository): EmulatorSeatState =
        EmulatorSeatState { BuildConfig.DEBUG && settings.settings.value.demoHud }
}
