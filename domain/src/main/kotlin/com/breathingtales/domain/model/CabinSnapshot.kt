package com.breathingtales.domain.model

/**
 * 연출 시작 전 실내 상태. 종료·중단 시 이 값으로 되돌린다 (기획서 5장 3번).
 * 원래 차량 값을 그대로 되돌려야 하므로 추상 값이 아닌 차량 단위로 저장하고,
 * 읽지 못한 항목은 null로 두어 복원에서 건너뛴다 (docs/dev/02_기술_확인.md 4장).
 */
data class CabinSnapshot(
    val hvacPowerOn: Boolean? = null,
    val fanSpeed: Int? = null,
    val airflow: Airflow? = null,
    val temperaturesC: Map<ZoneId, Float> = emptyMap(),
    val ambientMode: AmbientMode? = null,
    val ambientColorRgb: Int? = null,
    val ambientBrightness: Int? = null,
    val ambientAutoByDriveMode: Boolean? = null,
    val seatHeatLevels: Map<ZoneId, Int> = emptyMap(),
    /** 실내등 스위치. 읽지 못하면 null이고, 그러면 되돌릴 수 없으므로 연출도 바꾸지 않는다 */
    val cabinLight: LampSwitch? = null,
    /** 연출에 쓰는 독서등(뒷좌석) 스위치. 읽은 영역만 담고, 읽은 영역만 바꾼다 */
    val readingLights: Map<ZoneId, LampSwitch> = emptyMap(),
)

/** 앰비언트 조명 스위치. [AUTO]는 차량이 알아서 켜고 끄는 상태 */
enum class AmbientMode { OFF, ON, AUTO }

/** 실내등·독서등 스위치. [AUTO]는 문 열림 등에 따라 차량이 알아서 켜고 끄는 상태 */
enum class LampSwitch { OFF, ON, AUTO }

/** 어댑터가 차량 지원 범위 조회로 받은 제어 영역. 도메인은 값의 의미를 해석하지 않는다 */
@JvmInline
value class ZoneId(val raw: Int)
