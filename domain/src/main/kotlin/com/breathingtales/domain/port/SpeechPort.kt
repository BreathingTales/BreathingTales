package com.breathingtales.domain.port

/**
 * 동적 문장 읽기 (Pleos TTS, 08에서 구현). 아이 이름 같은 치환은 엔진이 끝낸 문장이 들어온다 (기획서 6-2).
 */
interface SpeechPort {
    /** 다 읽을 때까지 기다린다. 코루틴이 취소되면 읽기를 멈춘다. 실패하면 예외 */
    suspend fun speak(text: String)
}
