package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.ZoneId

/**
 * SafetyGuard가 마지막으로 처리한 제어를 종류별로 하나씩 담는다 (11 시연 HUD).
 * 동화가 요청한 추상 값과, 허용 범위로 줄여 실제로 차량에 보낸 값을 나란히 둔다.
 * 보낸 값이 null이면 차량이 지원하지 않거나 범위가 비어 보내지 않은 것이다.
 */
data class SafetyMonitor(
    val active: Boolean = false,
    val intensity: Intensity? = null,
    val fan: FanControl? = null,
    val airflow: Airflow? = null,
    val temperature: TemperatureControl? = null,
    val seatHeat: SeatHeatControl? = null,
    val ambient: AmbientControl? = null,
    val seatLift: SeatLiftControl? = null,
    val cabinLight: LampControl? = null,
    val spotlight: LampControl? = null,
    /** 마지막 복원에서 되돌리지 못한 항목. 복원 전이면 null */
    val restoreFailures: List<String>? = null,
) {
    data class FanControl(val requestedLevel: Int, val speed: Int?, val allowedMaxSpeed: Int?)

    data class TemperatureControl(
        val requestedDeltaC: Float,
        /** 영역별 (시작 온도, 보낸 온도). 보낸 온도가 null이면 그 영역은 바꾸지 않음 */
        val zones: Map<ZoneId, Pair<Float, Float?>>,
    )

    /** [sent]가 false면 차량이 지원하지 않거나 시작 상태를 읽지 못해(되돌릴 수 없어) 바꾸지 않음 */
    data class LampControl(val requestedOn: Boolean, val sent: Boolean)

    data class SeatHeatControl(val requestedLevel: Int, val level: Int?)

    data class AmbientControl(val colorRgb: Int, val requestedBrightness: Float, val brightness: Int?)

    /** 시트 들썩임 결과와 높이 위치 (시작 → 가장 높이 → 끝), 되돌리기까지 마친 횟수 / 요청 횟수. 읽지 못한 값은 null */
    data class SeatLiftControl(
        val outcome: SeatLiftOutcome,
        val start: Int? = null,
        val peak: Int? = null,
        val end: Int? = null,
        val done: Int = 0,
        val repeat: Int = 0,
    )
}

/** 시트 들썩임 결과 (기획서 5장 7번). 건너뛴 이유를 그대로 보여 준다 */
enum class SeatLiftOutcome {
    /** 올렸다가 시작 위치로 되돌림 */
    DONE,

    /** 부모 설정에서 꺼져 있음 */
    DISABLED,

    /** 연출마다 한 번만 */
    ALREADY_USED,

    /** 이 차량은 시트 높이 연출을 지원하지 않음 */
    UNSUPPORTED,

    /** 조수석에 앉아 벨트를 맨 사람이 없음 */
    NOT_OCCUPIED,

    /** 위치를 읽지 못함 (에뮬레이터 SDK는 늘 0이라 읽지 못한 것으로 본다) */
    POSITION_UNREADABLE,

    /** 이미 맨 위라 올릴 곳이 없음 */
    AT_TOP,

    /** 올렸는데 제자리로 되돌리지 못함 → 부모에게 알림 */
    UNRESTORED,
}
