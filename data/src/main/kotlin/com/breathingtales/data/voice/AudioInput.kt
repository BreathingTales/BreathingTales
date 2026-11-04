package com.breathingtales.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 참여 듣기에 쓰는 소리 공급원 (16kHz 모노 16비트). 앱은 마이크([MicCapture]),
 * 테스트는 녹음 파일을 넣어 마이크 다음 단계(STT·숨 불기 판정)를 실제 코드 그대로 검증한다.
 */
interface AudioInput {
    /** 수집하는 동안 소리를 낸다. 수집을 멈추면 마이크를 닫는다 */
    fun frames(): Flow<AudioFrame>
}

class AudioFrame(
    val timeMs: Long,
    /** 리틀 엔디언 16비트 PCM */
    val pcm: ByteArray,
    /** 이 구간의 음량 (dBFS) */
    val db: Float,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SILENT_DB = -90f

        fun of(timeMs: Long, pcm: ByteArray): AudioFrame {
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = samples.remaining()
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples.get(i).toDouble()
                sum += s * s
            }
            val rms = if (count == 0) 0.0 else sqrt(sum / count) / Short.MAX_VALUE
            return AudioFrame(timeMs, pcm, if (rms > 0) (20 * log10(rms)).toFloat() else SILENT_DB)
        }
    }
}
