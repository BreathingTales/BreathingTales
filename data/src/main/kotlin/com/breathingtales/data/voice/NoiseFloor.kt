package com.breathingtales.data.voice

import kotlin.math.max

/**
 * 배경 소음 추정 (08). 마이크마다, 장소마다 조용할 때 음량이 크게 다르다
 * (Galaxy Buds 조용한 방 약 -77, Buds FE 핸즈프리 + 에뮬레이터 약 -53 dBFS, 2026-11-24 측정).
 * 고정 기준으로는 소음이 큰 마이크에서 아무것도 안 해도 불기로 세어지므로, 소음보다 얼마나 큰지로 판정한다.
 *
 * - [average]: 최근 [windowMs] 평균 음량
 * - [floorDb]: 최근 [spanMs] 동안 [average]의 최솟값. 소리를 내는 사이사이 조용한 순간이 소음 수준이다
 * - 듣기 시작하고 [calibrateMs] 동안은 소음을 아직 모르므로 기준이 없다(null)
 */
class NoiseFloor(
    private val windowMs: Long = 200,
    private val spanMs: Long = 3_000,
    private val calibrateMs: Long = 500,
) {
    private val window = ArrayDeque<Pair<Long, Float>>()
    private val averages = ArrayDeque<Pair<Long, Float>>()
    private var firstAt: Long? = null
    private var lastAt: Long? = null

    /** 최근 [windowMs] 평균 음량 (dBFS) */
    var average: Float = SILENCE_DB
        private set

    fun onLevel(timeMs: Long, db: Float) {
        if (firstAt == null) firstAt = timeMs
        lastAt = timeMs
        window.addLast(timeMs to db)
        while (window.first().first < timeMs - windowMs) window.removeFirst()
        average = (window.sumOf { it.second.toDouble() } / window.size).toFloat()
        averages.addLast(timeMs to average)
        while (averages.first().first < timeMs - spanMs) averages.removeFirst()
    }

    /** 배경 소음 (dBFS). 듣기 시작하고 [calibrateMs] 전이면 null */
    val floorDb: Float?
        get() {
            val first = firstAt ?: return null
            val last = lastAt ?: return null
            if (last - first < calibrateMs) return null
            return averages.minOf { it.second }
        }

    /** 소음보다 [marginDb] 큰 음량. 아주 조용한 마이크에서도 [MIN_THRESHOLD_DB]보다 낮게는 잡지 않는다 */
    fun thresholdDb(marginDb: Float): Float? = floorDb?.let { max(it + marginDb, MIN_THRESHOLD_DB) }

    companion object {
        const val SILENCE_DB = -90f

        /** 기준의 하한. 디지털 무음(-90)에 가까운 마이크에서 작은 잡음까지 세지 않도록 */
        const val MIN_THRESHOLD_DB = -66f
    }
}
