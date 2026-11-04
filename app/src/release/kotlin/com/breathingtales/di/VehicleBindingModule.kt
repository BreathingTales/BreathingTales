package com.breathingtales.di

import com.breathingtales.data.vehicle.CarVehicleStateRepository
import com.breathingtales.data.vehicle.PleosVehicleAdapter
import com.breathingtales.domain.port.VehicleEffectPort
import com.breathingtales.domain.repository.VehicleStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 차량 연결 (release 빌드): 늘 Pleos. Fake는 debug 빌드에만 있다 (src/debug의 같은 이름 모듈) */
@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleBindingModule {
    @Binds
    abstract fun vehicleEffectPort(pleos: PleosVehicleAdapter): VehicleEffectPort

    @Binds
    abstract fun vehicleStateRepository(car: CarVehicleStateRepository): VehicleStateRepository
}
