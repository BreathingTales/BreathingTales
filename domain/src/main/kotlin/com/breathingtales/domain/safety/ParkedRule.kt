package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import kotlin.math.abs

/** 정차 판단 결과. 연출은 [Parked]일 때만 시작하고 유지한다 (기획서 3-1) */
sealed interface ParkedState {
    data object Parked : ParkedState

    data class NotParked(val reasons: Set<NotParkedReason>) : ParkedState
}

enum class NotParkedReason {
    UX_RESTRICTED,
    NOT_IN_PARK,
    MOVING,
    DOOR_OPEN,

    /** 연출 중 주차 브레이크가 체결 → 해제로 바뀜. 시작 조건에는 쓰지 않는다 */
    PARKING_BRAKE_RELEASED,
}

/**
 * 시작 조건: UX 제한 없음 + 기어 P + 속도 0 + 도어 닫힘 (docs/dev/02_기술_확인.md 2장).
 * 아직 받지 못한 값(null)은 그 조건을 채우지 못한 것으로 본다. 주차 브레이크는 시작 조건이 아니다.
 */
object ParkedRule {
    /** 속도 센서 잡음 허용치. 0.1 m/s = 0.36 km/h */
    const val STOPPED_SPEED_MPS = 0.1f

    fun evaluate(state: VehicleState): ParkedState {
        val reasons = buildSet {
            if (state.uxRestricted != false) add(NotParkedReason.UX_RESTRICTED)
            if (state.gear != Gear.PARK) add(NotParkedReason.NOT_IN_PARK)
            // NaN도 정차가 아니어야 하므로 "< 허용치"가 아닌 경우를 모두 주행으로 본다
            val speed = state.speedMps
            if (speed == null || !(abs(speed) < STOPPED_SPEED_MPS)) add(NotParkedReason.MOVING)
            if (state.doorsClosed != true) add(NotParkedReason.DOOR_OPEN)
        }
        return if (reasons.isEmpty()) ParkedState.Parked else ParkedState.NotParked(reasons)
    }
}

internal operator fun ParkedState.plus(reason: NotParkedReason): ParkedState.NotParked =
    ParkedState.NotParked((this as? ParkedState.NotParked)?.reasons.orEmpty() + reason)
