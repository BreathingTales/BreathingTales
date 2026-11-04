package com.breathingtales.domain.model

/**
 * 동화에 들어 있는 음원 하나. [path]는 story.json에 적힌 그대로 동화 폴더 기준 상대 경로다
 * (`Line.audio`, `Effect.Sound.audio`). 실제 파일 위치로 바꾸는 일은 오디오 어댑터(06)가 한다.
 */
data class AudioClip(
    val storyId: String,
    val path: String,
)
