package com.breathingtales.ui

import com.breathingtales.domain.model.BreathPhase
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.model.BlowSensitivity
import com.breathingtales.domain.safety.NotParkedReason
import com.breathingtales.domain.usecase.AbortReason
import com.breathingtales.domain.usecase.PauseReason

/** 화면 문구. 부모가 읽는 기준으로 짧게 쓴다. Android에 의존하지 않아 단위 테스트로 확인한다 */
object UiTexts {

    /** 시작 전 정차 안내. 정차 중이면 null */
    fun parkGuide(reasons: Set<NotParkedReason>): String? {
        if (reasons.isEmpty()) return null
        val drivingRelated = reasons.any {
            it == NotParkedReason.UX_RESTRICTED || it == NotParkedReason.NOT_IN_PARK || it == NotParkedReason.MOVING
        }
        return when {
            drivingRelated && NotParkedReason.DOOR_OPEN in reasons -> "차를 세우고 기어를 P로 놓은 뒤 문을 닫아 주세요"
            drivingRelated -> "차를 세우고 기어를 P로 놓아 주세요"
            NotParkedReason.DOOR_OPEN in reasons -> "문을 닫아 주세요"
            else -> "차를 세우고 기어를 P로 놓아 주세요"
        }
    }

    /** 이야기가 중간에 끝난 이유. 부모가 직접 멈춘 경우는 안내 없이 홈으로 가므로 null */
    fun abortMessage(reason: AbortReason): String? = when (reason) {
        is AbortReason.NotParked -> when {
            NotParkedReason.PARKING_BRAKE_RELEASED in reason.reasons -> "주차 브레이크가 풀려서 이야기를 멈췄어요"
            NotParkedReason.DOOR_OPEN in reason.reasons && reason.reasons.size == 1 -> "문이 열려서 이야기를 멈췄어요"
            else -> "차가 움직일 수 있는 상태가 되어 이야기를 멈췄어요"
        }
        AbortReason.AudioFocusUnavailable -> "통화 중에는 이야기를 시작할 수 없어요"
        AbortReason.StoppedByParent, AbortReason.Cancelled -> null
    }

    fun pauseMessage(reason: PauseReason): String = when (reason) {
        PauseReason.PARENT -> "잠시 멈췄어요"
        PauseReason.AUDIO_FOCUS -> "길 안내나 전화 때문에 잠시 멈췄어요"
    }

    /** 참여 안내. 아이에게 부모가 읽어 줄 수 있게 쓴다 */
    fun interactionGuide(interaction: Interaction, breathPhase: BreathPhase? = null): String {
        val keyword = interaction.keywords.firstOrNull()
        return when (interaction.type) {
            InteractionType.SHOUT -> "다 같이 외쳐요  “$keyword!”"
            InteractionType.BLOW -> "“후~” 하고 힘껏 불어요"
            InteractionType.SHOUT_OR_BLOW -> "“$keyword!” 외치거나  “후~” 불어요"
            InteractionType.BREATHE -> when (breathPhase) {
                BreathPhase.INHALE -> "코로 숨을 크게 들이마셔요"
                BreathPhase.EXHALE, null -> "“후~” 천천히 내쉬어요"
            }
        }
    }

    /** `{childName}` 자리에 아이 이름. 이름이 없으면 "친구" */
    fun fillChildName(text: String, childName: String): String =
        text.replace("{childName}", childName.trim().ifEmpty { "친구" })

    /** 앱 정보: 콘텐츠 출처. 대사 음원은 Qwen3-TTS, 그림은 FLUX.1-schnell(둘 다 Apache-2.0)로 만들었다 */
    val credits = listOf(
        "음성: Qwen3-TTS",
        "그림: FLUX.1-schnell로 생성",
        "아이 이름 부르기: Pleos TTS",
        "효과음: 숨쉬는 동화 팀 자체 제작",
    )

    /** 대본의 speaker id → 화면 표시. 모르는 id는 그대로 */
    fun speakerName(speaker: String): String = when (speaker) {
        "narrator" -> "해설"
        "wind" -> "바람"
        "sun" -> "해님"
        "porong" -> "포롱"
        else -> speaker
    }

    fun intensity(intensity: Intensity): String = when (intensity) {
        Intensity.LOW -> "약"
        Intensity.MEDIUM -> "중"
        Intensity.HIGH -> "강"
    }

    fun blowSensitivity(sensitivity: BlowSensitivity): String = when (sensitivity) {
        BlowSensitivity.LOW -> "둔하게"
        BlowSensitivity.NORMAL -> "보통"
        BlowSensitivity.HIGH -> "예민하게"
    }

    fun duration(seconds: Int): String {
        val minutes = (seconds + 30) / 60
        return if (minutes <= 0) "1분 이내" else "약 ${minutes}분"
    }
}
