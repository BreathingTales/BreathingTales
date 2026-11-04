package com.breathingtales.domain.model

/**
 * 장면 끝의 아이 참여 (기획서 3-3, 7장).
 * 성공할 때마다 [onSuccess]를 실행하고, [repeat]번 성공하거나 [timeoutSec]이 지나면 다음 장면으로 넘어간다.
 * 인식이 안 될 때를 위해 [fallbackButton] 터치 버튼을 항상 보여 준다.
 *
 * [InteractionType.BREATHE]는 듣지 않고 [breath]의 박자대로 숨쉬기를 [repeat]번 이끈다.
 * 한 번 들이쉬고 내쉴 때마다 성공 한 번으로 세고, [timeoutSec]은 쓰지 않으며, [fallbackButton]은 「그만하기」다.
 */
data class Interaction(
    val type: InteractionType,
    val keywords: List<String>,
    val onSuccess: List<Effect>,
    val fallbackButton: String,
    val timeoutSec: Int,
    val repeat: Int = 1,
    val breath: Breath? = null,
)

/** 숨 맞추기 박자. 들이쉬기 시작에 [onInhale], 내쉬기 시작에 [onExhale]을 실행한다 */
data class Breath(
    val inhaleSec: Float,
    val exhaleSec: Float,
    val onInhale: List<Effect> = emptyList(),
    val onExhale: List<Effect> = emptyList(),
) {
    companion object {
        const val MIN_SEC = 1f
        const val MAX_SEC = 10f
        const val MAX_CYCLES = 5
    }
}

enum class BreathPhase { INHALE, EXHALE }

enum class InteractionType {
    /** 외치기 (STT 키워드) */
    SHOUT,

    /** 숨 불기 (마이크 음량) */
    BLOW,

    /** 외치기 또는 숨 불기 */
    SHOUT_OR_BLOW,

    /** 숨 맞추기 (듣지 않고 박자에 맞춰 빛·바람이 바뀐다) */
    BREATHE;

    val usesKeywords: Boolean get() = this == SHOUT || this == SHOUT_OR_BLOW

    /** 마이크로 듣는지. 숨 맞추기는 듣지 않는다 */
    val listens: Boolean get() = this != BREATHE
}
