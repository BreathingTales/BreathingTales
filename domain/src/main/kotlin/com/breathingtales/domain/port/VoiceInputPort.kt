package com.breathingtales.domain.port

import com.breathingtales.domain.model.InteractionType
import kotlinx.coroutines.flow.Flow

/**
 * 아이 참여 듣기 (외치기는 Pleos STT, 숨 불기는 마이크 음량. 08에서 구현).
 */
interface VoiceInputPort {
    /**
     * 수집하는 동안 마이크를 켜고, 참여에 성공할 때마다 신호를 하나 낸다. 수집을 멈추면 마이크를 끈다.
     * [keywords]는 외치기([InteractionType.usesKeywords])에서만 쓴다.
     */
    fun listen(type: InteractionType, keywords: List<String>): Flow<VoiceSignal>
}

sealed interface VoiceSignal {
    /** 키워드를 외침. [heard]는 인식된 문장 */
    data class Shout(val heard: String) : VoiceSignal

    data object Blow : VoiceSignal
}
