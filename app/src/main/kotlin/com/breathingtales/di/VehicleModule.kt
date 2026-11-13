package com.breathingtales.di

import com.breathingtales.data.vehicle.EmulatorVehicleState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 차량 연결 중 빌드 종류와 무관한 것. Fake ↔ Pleos 교체는 빌드 종류별 [VehicleBindingModule] (src/debug, src/release) */
@Module
@InstallIn(SingletonComponent::class)
object VehicleModule {
    /**
     * Pleos 에뮬레이터에서만 시트 위치·착석을 Virtual Console에서 읽는다 (debug·release 모두).
     * 심사가 에뮬레이터에서 release APK로 이뤄지므로 시트 들썩임도 그대로 확인할 수 있게 한다. 실차에서는 늘 꺼져 SDK 값만 본다
     */
    @Provides
    @Singleton
    fun emulatorSeatState(): EmulatorVehicleState = EmulatorVehicleState { EmulatorVehicleState.isEmulator }
}
