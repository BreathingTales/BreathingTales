package com.breathingtales.domain.repository

import com.breathingtales.domain.model.VehicleState
import kotlinx.coroutines.flow.StateFlow

/** 정차 판단용 차량 상태 (05에서 AAOS + Pleos Door로 구현) */
interface VehicleStateRepository {
    /** 아직 받지 못한 항목은 null. SafetyGuard가 제어 직전에 현재 값을 바로 읽으므로 StateFlow로 둔다 */
    val state: StateFlow<VehicleState>
}
