package com.breathingtales.domain.model

/**
 * 동화 한 편 (기획서 8-3). `assets/stories/<id>/story.json`에서 만들어진다.
 * 파일 경로(`audio`, `image`, `cover`)는 모두 동화 폴더 기준 상대 경로다.
 */
data class Story(
    val id: String,
    val title: String,
    val ageRange: String,
    val durationSec: Int,
    val scenes: List<Scene>,
    val familyTalkCards: List<String>,
    /** 홈 목록 표지 그림 (2:1) */
    val cover: String? = null,
)

/**
 * 장면. 재생 순서: 대사를 차례로 재생하고, 대사가 끝나면 [interaction]을 기다린 뒤 다음 장면으로 넘어간다.
 * [effects]의 시각은 장면 시작 기준이다.
 */
data class Scene(
    val id: String,
    val lines: List<Line>,
    val effects: List<TimedEffect>,
    val interaction: Interaction? = null,
    val image: String? = null,
)

/**
 * 대사. [audio]가 있으면 미리 만든 음원을 재생하고, 없으면 [text]를 Pleos TTS로 읽는다 (기획서 6-2).
 * TTS 문장에는 `{childName}` 같은 치환 자리를 쓸 수 있다.
 */
data class Line(
    val speaker: String,
    val text: String,
    val audio: String? = null,
    /** 대사가 끝난 뒤 쉬는 시간(초). 아이가 흉내 낼 틈을 준다 (10 대본 재작성) */
    val pauseAfterSec: Float = 0f,
) {
    val isTts: Boolean get() = audio == null

    companion object {
        const val MAX_PAUSE_SEC = 10f
    }
}

/** 장면 시작 후 [atSec]초에 실행할 효과 */
data class TimedEffect(
    val atSec: Double,
    val effect: Effect,
)
