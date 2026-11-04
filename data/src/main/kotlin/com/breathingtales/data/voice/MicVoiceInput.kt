package com.breathingtales.data.voice

import android.os.SystemClock
import android.util.Log
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.port.VoiceInputPort
import com.breathingtales.domain.port.VoiceSignal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VoiceInputPort] 구현. 소리 공급원 하나([AudioInput], 앱에서는 마이크 [MicCapture])를 열고,
 * - 숨 불기: 음량을 [BlowRule]로 판정
 * - 외치기: 같은 소리를 [PleosStt]에 넘겨 [KeywordMatcher]로 판정
 * 두 결과는 [SuccessFilter]로 걸러 한 번의 소리(덩어리)가 한 번만 세어지게 한다.
 * 마이크 권한(`RECORD_AUDIO`)은 09 화면이 받는다. 권한이 없으면 수집이 예외로 끝나고, 엔진은 대체 버튼만 받는다.
 */
@Singleton
class MicVoiceInput @Inject constructor(
    private val audioInput: AudioInput,
    private val stt: PleosStt,
) : VoiceInputPort {

    /** 숨 불기 기준(dBFS). 부모 설정의 민감도로 바꾼다 (09) */
    @Volatile
    var blowThresholdDb: Float = BlowRule.DEFAULT_THRESHOLD_DB

    override fun listen(type: InteractionType, keywords: List<String>): Flow<VoiceSignal> = channelFlow {
        val filter = SuccessFilter(blowThresholdDb)
        val lock = Any()
        val blow = if (type != InteractionType.SHOUT) BlowRule(blowThresholdDb) else null
        // STT가 늦어도 마이크 읽기를 막지 않도록 오래된 소리부터 버린다
        val audio = if (type.usesKeywords) Channel<ByteArray>(64, BufferOverflow.DROP_OLDEST) else null

        if (audio != null) {
            val matcher = KeywordMatcher(keywords)
            launch {
                stt.recognize(audio.consumeAsFlow()).collect { event ->
                    val (text, final) = when (event) {
                        is PleosStt.Event.Partial -> event.text to false
                        is PleosStt.Event.Final -> event.text to true
                        else -> return@collect
                    }
                    val hit = matcher.match(text) != null
                    val counted = synchronized(lock) { filter.onSpeech(SystemClock.elapsedRealtime(), hit, final) }
                    Log.d(TAG, "STT ${if (final) "최종" else "중간"} '$text'${if (counted) " → 성공" else ""}")
                    if (counted) send(VoiceSignal.Shout(text))
                }
            }
        }

        var levelLogAt = 0L
        var levelMax = -90f
        audioInput.frames().collect { frame ->
            audio?.trySend(frame.pcm)
            synchronized(lock) { filter.onLevel(frame.timeMs, frame.db) }
            // 진단용: 1초마다 최대 음량 (계속 -90 근처면 마이크가 막힌 것)
            levelMax = maxOf(levelMax, frame.db)
            if (frame.timeMs - levelLogAt >= 1_000) {
                Log.v(TAG, "마이크 최대 %.1f dBFS".format(levelMax))
                levelLogAt = frame.timeMs
                levelMax = -90f
            }
            if (blow?.onLevel(frame.timeMs, frame.db) == true) {
                val counted = synchronized(lock) { filter.onBlow() }
                Log.d(TAG, "숨 불기 %.1f dBFS%s".format(frame.db, if (counted) " → 성공" else ""))
                if (counted) send(VoiceSignal.Blow)
            }
        }
    }

    private companion object {
        const val TAG = "BTVoice"
    }
}
