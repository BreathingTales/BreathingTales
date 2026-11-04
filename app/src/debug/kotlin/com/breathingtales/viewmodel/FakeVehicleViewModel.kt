package com.breathingtales.viewmodel

import androidx.lifecycle.ViewModel
import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort
import com.breathingtales.data.vehicle.fake.FakeVehicleStateRepository
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.VehicleState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake 차량 조작 패널 (`-PfakeVehicle=true` 빌드에서만 보인다).
 * VehicleModule이 Fake를 고르면 여기서 받는 객체가 엔진이 쓰는 것과 같은 싱글턴이다.
 */
@HiltViewModel
class FakeVehicleViewModel @Inject constructor(
    private val vehicle: FakeVehicleStateRepository,
    private val effects: FakeVehicleEffectPort,
) : ViewModel() {

    val state: StateFlow<VehicleState> = vehicle.state
    val cabin: StateFlow<CabinSnapshot> = effects.cabin
    val log: StateFlow<List<String>> = effects.log

    fun park() = vehicle.park()

    fun drive() = vehicle.drive()

    fun setDoorOpen(open: Boolean) = vehicle.setDoorOpen(open)

    fun setParkingBrake(on: Boolean) = vehicle.setParkingBrake(on)
}
