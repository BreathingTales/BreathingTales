package com.breathingtales.data.voice

import com.breathingtales.domain.model.BlowSensitivity

/**
 * 숨 불기 판정 (08). 마이크 음량(dBFS) 표본을 차례로 받아, 불기 한 번마다 한 번 성공을 낸다.
 *
 * - 최근 [windowMs] 평균이 [thresholdDb] 이상인 상태가 [holdMs] 이어지면 성공
 * - 길게 한 번 불면 한 번만 센다. 평균이 기준 아래로 [releaseMs] 내려가야 다음 불기를 센다
 *
 * 기본값은 Galaxy Buds 측정값(02 문서 8장): 조용할 때 약 -77, "후~" 할 때 -44 ~ -51 dBFS.
 * 마이크마다 다르므로 [thresholdDb]는 부모 설정(민감도)으로 바꾼다.
 */
class BlowRule(
    val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
    private val holdMs: Long = 500,
    private val releaseMs: Long = 300,
    private val windowMs: Long = 200,
) {
    private val window = ArrayDeque<Pair<Long, Float>>()
    private var aboveSince: Long? = null
    private var belowSince: Long? = null
    private var armed = true

    /** [timeMs] 시각의 음량 표본. 이 표본으로 불기 한 번이 완성되면 true */
    fun onLevel(timeMs: Long, db: Float): Boolean {
        window.addLast(timeMs to db)
        while (window.first().first < timeMs - windowMs) window.removeFirst()
        val average = window.sumOf { it.second.toDouble() } / window.size

        if (average >= thresholdDb) {
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
        const val DEFAULT_THRESHOLD_DB = -60f

        /** 부모 설정의 민감도 → 기준 음량. 민감할수록 작은 소리도 불기로 센다 */
        fun thresholdFor(sensitivity: BlowSensitivity): Float = when (sensitivity) {
            BlowSensitivity.LOW -> -54f
            BlowSensitivity.NORMAL -> DEFAULT_THRESHOLD_DB
            BlowSensitivity.HIGH -> -66f
        }
    }
}
