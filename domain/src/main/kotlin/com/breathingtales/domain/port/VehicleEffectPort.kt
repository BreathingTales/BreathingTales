package com.breathingtales.domain.port

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.ZoneId

/**
 * 실내 제어 포트 (05에서 Fake·Pleos로 구현). 값은 모두 차량 단위이고, 허용 범위 제한은
 * [com.breathingtales.domain.safety.SafetyGuard]가 끝낸 값만 들어온다. 이 포트는 SafetyGuard만 쓴다.
 * 차종이 지원하지 않는 기능은 어댑터가 조용히 건너뛴다.
 * 창문 제어는 일부러 두지 않는다 (아이 손 끼임 위험, 기획서 5장).
 */
interface VehicleEffectPort {
    suspend fun capabilities(): CabinCapabilities

    /** 읽을 수 있는 값만 채운다. 공조 전원이 꺼져 있으면 풍량·풍향·온도는 비어 있다 */
    suspend fun readSnapshot(): CabinSnapshot

    /** 전원 이벤트로 실제로 바뀐 것을 확인한 뒤 돌아온다 (02 문서 4장) */
    suspend fun setHvacPower(on: Boolean)

    suspend fun setFanSpeed(speed: Int)

    suspend fun setAirflow(airflow: Airflow)

    suspend fun setTemperature(zone: ZoneId, celsius: Float)

    suspend fun setAmbientMode(mode: AmbientMode)

    /** 0xRRGGBB. 알파는 어댑터가 붙인다 */
    suspend fun setAmbientColor(rgb: Int)

    suspend fun setAmbientBrightness(brightness: Int)

    suspend fun setAmbientAutoByDriveMode(enabled: Boolean)

    suspend fun setSeatHeat(zone: ZoneId, level: Int)

    suspend fun setCabinLight(switch: LampSwitch)

    suspend fun setReadingLight(zone: ZoneId, switch: LampSwitch)

    /**
     * 시트 위치 ([CabinCapabilities.seatHeightRange]·[CabinCapabilities.seatTiltRange] 안의 값). 읽지 못하면 null.
     * 믿을 수 없는 값(에뮬레이터 SDK는 실제 위치와 무관하게 늘 0)도 null로 돌려준다. 0은 진짜 맨 아래일 때만
     */
    suspend fun readSeatPosition(zone: ZoneId, axis: SeatAxis): Int?

    /** 그 좌석에 사람이 앉아 있고 안전벨트를 맸는지. 하나라도 읽지 못하면 false */
    suspend fun isSeatOccupiedAndBuckled(zone: ZoneId): Boolean

    /**
     * 시트 이동. 위치를 정하는 API가 아니라 [SeatMove.STOP]을 보낼 때까지 계속 움직인다 (02 문서 4장).
     * 멈추는 일은 SafetyGuard가 위치를 읽으며 한다
     */
    suspend fun moveSeat(zone: ZoneId, axis: SeatAxis, move: SeatMove)
}

/** [SeatMove.UP]은 위치 값이 커지는 방향 */
enum class SeatMove { UP, DOWN, STOP }

/** 들썩임에 쓰는 시트 축. 등받이는 뒷좌석 아이 쪽으로 움직이므로 일부러 두지 않는다 (기획서 5장 7번) */
enum class SeatAxis {
    /** 방석 높이. 값이 클수록 높다 */
    HEIGHT,

    /** 방석 앞쪽 각도. 값이 클수록 앞쪽이 들려 몸이 뒤로 기운다 (AAOS `SEAT_TILT_POS`) */
    TILT,
}

/** 어댑터가 `check…Capabilities`로 받은 차량 지원 범위 */
data class CabinCapabilities(
    /** null이면 풍량 제어 없음 */
    val fanSpeedRange: IntRange?,
    /** null이면 차량 범위를 모르므로 SafetyLimits만 적용 */
    val temperatureRangeC: ClosedFloatingPointRange<Float>?,
    val temperatureStepC: Float,
    /** null이면 앰비언트 조명 없음 */
    val ambientBrightnessRange: IntRange?,
    /** 연출에 쓰는 뒷좌석 열선 영역. 비어 있으면 열선 없음 */
    val seatHeatZones: List<ZoneId>,
    val seatHeatMaxLevel: Int,
    /**
     * 「붕 떠오르는」 연출에 쓸 수 있는 시트 영역: 높이 이동·위치 읽기·착석·벨트를 모두 지원하는 운전석이 아닌 앞좌석.
     * 비어 있으면 시트 연출 없음 (기획서 5장 7번)
     */
    val seatLiftZones: List<ZoneId> = emptyList(),
    /** 시트 높이 위치 범위. null이면 시트 연출 없음 */
    val seatHeightRange: IntRange? = null,
    /** [seatLiftZones] 좌석의 방석 기울기 위치 범위. null이면 기울기 없이 높이만 움직인다 */
    val seatTiltRange: IntRange? = null,
    /** 실내등을 켜고 끌 수 있는지 */
    val cabinLight: Boolean = false,
    /** 「햇살 한 줄기」에 쓸 뒷좌석 독서등 영역. 비어 있으면 독서등 연출 없음 */
    val spotlightZones: List<ZoneId> = emptyList(),
)
