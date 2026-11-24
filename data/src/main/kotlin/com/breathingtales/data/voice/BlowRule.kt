package com.breathingtales.data.voice

import com.breathingtales.domain.model.BlowSensitivity

/**
 * 숨 불기 판정 (08). [floor]에 들어온 음량을 차례로 보고, 불기 한 번마다 한 번 성공을 낸다.
 * [floor]는 부르는 쪽이 먼저 채운다 (같은 소리로 [SuccessFilter]도 판정하므로 함께 쓴다).
 *
 * - 최근 200ms 평균이 배경 소음보다 [marginDb] 큰 상태가 [holdMs] 이어지면 성공
 * - 길게 한 번 불면 한 번만 센다. 평균이 기준 아래로 [releaseMs] 내려가야 다음 불기를 센다
 * - 듣기 시작 직후 소음을 재는 동안(0.5초)은 세지 않는다
 *
 * 고정 기준(-60 dBFS)이었을 때는 소음이 -53인 마이크에서 듣기를 시작하자마자 불기로 세고,
 * 소음이 기준 아래로 내려가지 않아 그 뒤 진짜 불기를 하나도 세지 못했다 (2026-11-24 에뮬레이터, Buds FE).
 * 측정: 소음 약 -53, "후~"·말소리 약 -30 ~ -46 dBFS.
 */
class BlowRule(
    private val floor: NoiseFloor,
    val marginDb: Float = DEFAULT_MARGIN_DB,
    private val holdMs: Long = 500,
    private val releaseMs: Long = 300,
) {
    private var aboveSince: Long? = null
    private var belowSince: Long? = null
    private var armed = true

    /** [timeMs] 시각까지 [floor]에 들어온 소리로 불기 한 번이 완성되면 true */
    fun onLevel(timeMs: Long): Boolean {
        val threshold = floor.thresholdDb(marginDb) ?: return false
        if (floor.average >= threshold) {
            belowSince = null
            val since = aboveSince ?: timeMs.also { aboveSince = it }
            if (armed && timeMs - since >= holdMs) {
                armed = false
                return true
            }
        } else {
            aboveSince = null
            val since = belowSince ?: timeMs.also { belowSince = it }
            if (timeMs - since >= releaseMs) armed = true
        }
        return false
    }

    companion object {
        /** 배경 소음보다 이만큼(dB) 커야 불기로 센다 */
        const val DEFAULT_MARGIN_DB = 10f

        /** 부모 설정의 민감도 → 소음과의 차이. 예민할수록 작은 소리도 불기로 센다 */
        fun marginFor(sensitivity: BlowSensitivity): Float = when (sensitivity) {
            BlowSensitivity.LOW -> 13f
            BlowSensitivity.NORMAL -> DEFAULT_MARGIN_DB
            BlowSensitivity.HIGH -> 7f
        }
    }
}
