package com.breathingtales.di

import com.breathingtales.BuildConfig
import com.breathingtales.data.vehicle.CarVehicleStateRepository
import com.breathingtales.data.vehicle.PleosVehicleAdapter
import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort
import com.breathingtales.data.vehicle.fake.FakeVehicleStateRepository
import com.breathingtales.domain.port.VehicleEffectPort
import com.breathingtales.domain.repository.VehicleStateRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Fake ↔ Pleos 교체 지점 (debug 빌드). 기본은 Pleos이고, `./gradlew installDebug -PfakeVehicle=true`로 빌드하면 Fake를 쓴다.
 * release 빌드에는 Fake 코드가 없고 같은 이름의 모듈(src/release)이 늘 Pleos를 고른다.
 * Provider로 받아 쓰지 않는 쪽은 만들지 않는다 (Pleos 쪽은 만들 때 SDK·Car 서비스에 연결한다).
 */
@Module
@InstallIn(SingletonComponent::class)
object VehicleBindingModule {
    @Provides
    @Singleton
    fun vehicleEffectPort(
        fake: Provider<FakeVehicleEffectPort>,
        pleos: Provider<PleosVehicleAdapter>,
    ): VehicleEffectPort = if (BuildConfig.FAKE_VEHICLE) fake.get() else pleos.get()

    @Provides
    @Singleton
    fun vehicleStateRepository(
        fake: Provider<FakeVehicleStateRepository>,
        car: Provider<CarVehicleStateRepository>,
    ): VehicleStateRepository = if (BuildConfig.FAKE_VEHICLE) fake.get() else car.get()
}
