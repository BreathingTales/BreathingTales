package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.port.CabinCapabilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 추상 효과 값을 허용 범위 안의 차량 값으로 바꾼다 (기획서 5장, docs/dev/03_도메인_모델.md 효과 표).
 * 반환값 null은 "바꾸지 않음"이다.
 */
class SafetyPolicy(
    private val capabilities: CabinCapabilities,
    private val intensity: Intensity,
    private val limits: SafetyLimits = SafetyLimits(),
) {

    /**
     * 바람 단계(0~5) → 풍량. 허용 풍량은 최대 풍량 × [SafetyLimits.maxFanRatio]이고,
     * 강도별 위쪽 끝은 최저 풍량과 허용 풍량 사이의 [Intensity.fanSpan] 지점이다.
     * 단계는 최저 풍량~위쪽 끝을 고르게 나눈다.
     * 전원을 끄지 않고는 바람을 멈출 수 없으므로 0단계는 최저 풍량이다.
     */
    fun fanSpeed(level: Int): Int? {
        val range = capabilities.fanSpeedRange ?: return null
        val allowed = floor(range.last * limits.maxFanRatio).toInt().coerceAtLeast(range.first)
        val top = (range.first + (allowed - range.first) * intensity.fanSpan).roundToInt()
        val ratio = level.coerceIn(0, Effect.FanLevel.MAX).toFloat() / Effect.FanLevel.MAX
        return (range.first + (top - range.first) * ratio).roundToInt()
    }

    /**
     * 시작 온도 기준 상대값 → 설정 온도. 20~26℃, 시작값 ±2℃, 차량 범위 안에서 차량 단위(0.5℃)에 맞춘다.
     * 범위가 비거나, 줄인 결과가 요청과 반대 방향이면 바꾸지 않는다
     * (예: 시작 27℃에서 따뜻하게 요청 → 26℃로 내려가는 일을 막음).
     */
    fun temperature(baselineC: Float, deltaC: Float): Float? {
        val scaled = deltaC * intensity.scale
        if (scaled == 0f) return baselineC

        var low = max(limits.minTemperatureC, baselineC - limits.maxTemperatureDeltaC)
        var high = min(limits.maxTemperatureC, baselineC + limits.maxTemperatureDeltaC)
        capabilities.temperatureRangeC?.let {
            low = max(low, it.start)
            high = min(high, it.endInclusive)
        }
        val step = capabilities.temperatureStepC
        val snap: (Float) -> Float = { if (step > 0f) (it / step).roundToInt() * step else it }
        if (step > 0f) {
            low = ceil(low / step) * step
            high = floor(high / step) * step
        }
        if (low > high) return null

        val target = snap(baselineC + scaled).coerceIn(low, high)
        return if ((target - baselineC) * scaled > 0f) target else null
    }

    /** 열선 단계. 1단까지로 낮춘다. 1단이 가장 약한 단계라 강도는 곱하지 않는다 */
    fun seatHeat(level: Int): Int? {
        if (capabilities.seatHeatZones.isEmpty()) return null
        return level.coerceIn(0, min(limits.maxSeatHeaterLevel, capabilities.seatHeatMaxLevel))
    }

    /** 밝기(0.0~1.0) → 차량 밝기. 강도를 곱한다 */
    fun ambientBrightness(brightness: Float): Int? {
        val range = capabilities.ambientBrightnessRange ?: return null
        val ratio = brightness.coerceIn(0f, 1f) * intensity.scale
        return (range.first + (range.last - range.first) * ratio).roundToInt()
    }
}
