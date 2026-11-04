package com.breathingtales.data.vehicle.fake

import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.repository.VehicleStateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 정차·주행·도어 열림을 직접 바꿀 수 있는 차량 상태. 디버그 화면(09)과 테스트에서 쓴다 */
@Singleton
class FakeVehicleStateRepository @Inject constructor() : VehicleStateRepository {

    private val _state = MutableStateFlow(PARKED)
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    /** P, 정지, 문 닫힘. 주차 브레이크는 에뮬레이터 기본값처럼 풀려 있다 */
    fun park() = _state.update { PARKED.copy(parkingBrakeOn = it.parkingBrakeOn) }

    /** D로 출발 */
    fun drive(speedMps: Float = 5f) =
        _state.update { it.copy(uxRestricted = true, gear = Gear.DRIVE, speedMps = speedMps) }

    fun setDoorOpen(open: Boolean) = _state.update { it.copy(doorsClosed = !open) }

    fun setParkingBrake(on: Boolean) = _state.update { it.copy(parkingBrakeOn = on) }

    companion object {
        val PARKED = VehicleState(
            uxRestricted = false,
            gear = Gear.PARK,
            speedMps = 0f,
            doorsClosed = true,
            parkingBrakeOn = false,
        )
    }
}
