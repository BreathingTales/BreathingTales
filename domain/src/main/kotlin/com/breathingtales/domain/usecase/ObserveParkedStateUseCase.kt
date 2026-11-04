package com.breathingtales.domain.usecase

import com.breathingtales.domain.repository.VehicleStateRepository
import com.breathingtales.domain.safety.NotParkedReason
import com.breathingtales.domain.safety.ParkedRule
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.domain.safety.plus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** 차량 상태를 정차 여부로 바꾼다 (기획서 3-1) */
class ObserveParkedStateUseCase @Inject constructor(
    private val vehicleStates: VehicleStateRepository,
) {
    /** 시작 화면용. 시작 조건만 본다 */
    fun beforeShow(): Flow<ParkedState> =
        vehicleStates.state.map(ParkedRule::evaluate).distinctUntilChanged()

    /**
     * 연출 중 감시용. 수집을 시작한 때부터 시작 조건과 함께 주차 브레이크 체결 → 해제를 본다.
     * 브레이크가 한 번 풀리면 다시 체결해도 이 수집이 끝날 때까지 정차 아님으로 남는다.
     */
    fun duringShow(): Flow<ParkedState> = flow {
        var brakeWasOn = false
        var brakeReleased = false
        vehicleStates.state.collect { state ->
            when (state.parkingBrakeOn) {
                true -> brakeWasOn = true
                false -> if (brakeWasOn) brakeReleased = true
                null -> Unit
            }
            val parked = ParkedRule.evaluate(state)
            emit(if (brakeReleased) parked + NotParkedReason.PARKING_BRAKE_RELEASED else parked)
        }
    }.distinctUntilChanged()
}
