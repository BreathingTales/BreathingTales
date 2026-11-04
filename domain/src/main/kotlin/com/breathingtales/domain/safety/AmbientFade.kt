package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Effect
import kotlin.math.roundToInt

/** 무드등 색(0xRRGGBB)과 밝기. 밝기는 차량 단위 */
data class AmbientValue(val colorRgb: Int, val brightness: Int)

/**
 * 무드등이 [from]에서 [to]로 서서히 바뀌는 전환 한 번 (`fadeSec`). 값은 [SafetyGuard]가 허용 범위로 줄인 차량 단위다.
 * Pleos SDK에는 전환 시간을 받는 조명 API가 없어서, 엔진이 효과 시계에 맞춰 [at]의 중간값을 나눠 보낸다.
 * @property requested 동화가 요청한 효과 (시연 HUD 기록용). 복원 전환이면 null
 */
class AmbientFade internal constructor(
    val from: AmbientValue,
    val to: AmbientValue,
    val requested: Effect.Ambient?,
) {
    /** [progress](0~1) 지점의 값. 색은 R·G·B를 따로 잇고, 밝기는 정수로 반올림한다 */
    fun at(progress: Float): AmbientValue {
        val t = progress.coerceIn(0f, 1f)
        if (t >= 1f) return to
        fun lerp(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
        fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF
        val color = listOf(16, 8, 0).fold(0) { acc, shift ->
            acc or (lerp(channel(from.colorRgb, shift), channel(to.colorRgb, shift)) shl shift)
        }
        return AmbientValue(color, lerp(from.brightness, to.brightness))
    }
}
