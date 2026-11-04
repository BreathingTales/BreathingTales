package com.breathingtales.domain.usecase

import com.breathingtales.domain.model.BreathPhase
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.Line
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.NotParkedReason

/**
 * 동화 재생 상태 (07). `Idle → Preparing → Playing ⇄ WaitingInteraction (⇄ Paused) → Finished / Aborted`
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState

    /** 실내 상태 저장, 오디오 포커스 요청 중 */
    data object Preparing : PlaybackState

    /** 진행 중인 상태. [Paused]가 멈추기 전 상태로 들고 있다 */
    sealed interface Active : PlaybackState {
        val sceneIndex: Int
        val sceneId: String
    }

    data class Playing(
        override val sceneIndex: Int,
        override val sceneId: String,
        val lineIndex: Int,
        val line: Line,
    ) : Active

    data class WaitingInteraction(
        override val sceneIndex: Int,
        override val sceneId: String,
        val interaction: Interaction,
        val successes: Int,
        /** 숨 맞추기([com.breathingtales.domain.model.InteractionType.BREATHE])에서 지금 들이쉬는지 내쉬는지 */
        val breathPhase: BreathPhase? = null,
    ) : Active

    data class Paused(val reason: PauseReason, val during: Active) : PlaybackState

    /** 끝까지 재생하고 복원함. [restoreFailures]는 되돌리지 못한 항목 */
    data class Finished(val restoreFailures: List<String>) : PlaybackState

    data class Aborted(val reason: AbortReason, val restoreFailures: List<String>) : PlaybackState
}

enum class PauseReason {
    /** 부모가 일시정지를 누름 */
    PARENT,

    /** 내비 안내·전화 등으로 오디오 포커스를 잃음. 다시 듣기는 부모가 누른다 */
    AUDIO_FOCUS,
}

sealed interface AbortReason {
    /** 시작할 때 정차가 아니었거나, 연출 중 정차가 풀림 (주차 브레이크 해제 포함) */
    data class NotParked(val reasons: Set<NotParkedReason>) : AbortReason

    /** 통화 중 등으로 오디오 포커스를 받지 못해 시작하지 않음 */
    data object AudioFocusUnavailable : AbortReason

    /** 부모가 중단을 누름 */
    data object StoppedByParent : AbortReason

    /** 재생을 맡긴 코루틴이 취소됨 (화면을 떠남 등) */
    data object Cancelled : AbortReason
}

/** 부모 설정 (09에서 화면으로 받는다) */
data class PlaySettings(
    val intensity: Intensity = Intensity.MEDIUM,
    /** TTS 대사의 `{childName}` 자리에 들어간다 */
    val childName: String,
    /** 시트 움직임 허용 (기획서 5장 7번). 기본 꺼짐 */
    val seatMotion: Boolean = false,
)
