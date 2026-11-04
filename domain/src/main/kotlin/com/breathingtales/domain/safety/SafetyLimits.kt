package com.breathingtales.domain.safety

/** 차량 제어 허용 범위 (기획서 5장 Safety Guard). 계산은 [SafetyPolicy]가 한다 */
data class SafetyLimits(
    val minTemperatureC: Float = 20f,
    val maxTemperatureC: Float = 26f,
    val maxTemperatureDeltaC: Float = 2f,
    val maxFanRatio: Float = 0.8f,
    val maxSeatHeaterLevel: Int = 1,
    /** 시트를 올리는 시간 상한 (기획서 5장 7번) */
    val maxSeatLiftSec: Float = 1f,
    /** 시트를 올리는 폭 상한. 높이 범위에 대한 비율 */
    val maxSeatLiftRatio: Float = 0.1f,
    /** 연출 한 번에 올렸다 되돌리기를 되풀이하는 횟수 상한 */
    val maxSeatLiftRepeat: Int = 3,
)
