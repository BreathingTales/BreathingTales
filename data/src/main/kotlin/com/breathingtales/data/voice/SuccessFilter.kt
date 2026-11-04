package com.breathingtales.data.voice

/**
 * 한 번의 외침·불기가 성공 여러 번으로 세어지지 않게 거른다 (08).
 *
 * **소리 덩어리마다 한 번만 센다.** 음량이 [thresholdDb] 이상으로 올라가면 덩어리가 시작되고,
 * [releaseMs] 넘게 조용하면 끝난다. 외치는 소리도 크고 길어 불기로 잡히는데, 외치기 결과(STT)는
 * 엔진 사정으로 1~2초 늦게 올 수 있어 시간 간격만으로는 구분할 수 없다. 그래서 STT 결과는
 * 가장 최근 덩어리의 것으로 보고, 그 덩어리가 이미 성공으로 세어졌으면 버린다.
 *
 * 외치기 한 발화에서 중간 결과가 여러 번 맞아도 처음 한 번만 센다(최종 결과로 발화가 끝난다).
 */
class SuccessFilter(
    private val thresholdDb: Float = BlowRule.DEFAULT_THRESHOLD_DB,
    private val releaseMs: Long = 300,
    /** 이보다 오래전에 끝난 덩어리에는 STT 결과를 붙이지 않는다 (조용한 말소리 등) */
    private val speechAttachMs: Long = 4_000,
    private val utteranceTimeoutMs: Long = 3_000,
) {
    private var segment = 0
    private var loud = false
    private var lastLoudAt: Long? = null
    private val counted = mutableSetOf<Int>()
    private var matchedAt: Long? = null

    fun onLevel(timeMs: Long, db: Float) {
        if (db >= thresholdDb) {
            if (!loud) {
                val last = lastLoudAt
                if (last == null || timeMs - last > releaseMs) segment++
                loud = true
            }
            lastLoudAt = timeMs
        } else if (loud) {
            val last = lastLoudAt
            if (last != null && timeMs - last > releaseMs) loud = false
        }
    }

    /** 숨 불기 한 번. 성공으로 셀 때 true */
    fun onBlow(): Boolean = counted.add(segment)

    /** STT 결과 하나. [matched]는 키워드가 맞았는지. 성공으로 셀 때 true */
    fun onSpeech(timeMs: Long, matched: Boolean, final: Boolean): Boolean {
        matchedAt?.let { if (timeMs - it > utteranceTimeoutMs) matchedAt = null }
        val alreadyMatched = matchedAt != null
        if (final) matchedAt = null
        if (!matched || alreadyMatched) return false
        if (!final) matchedAt = timeMs
        val last = lastLoudAt
        // 소리 덩어리 없이 온 결과(기준보다 작은 말소리)는 새 덩어리로 본다
        if (last == null || timeMs - last > speechAttachMs) segment++
        return counted.add(segment)
    }
}
