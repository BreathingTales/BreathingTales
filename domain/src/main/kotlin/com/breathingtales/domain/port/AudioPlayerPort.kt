package com.breathingtales.domain.port

import com.breathingtales.domain.model.AudioClip
import kotlinx.coroutines.flow.StateFlow

/**
 * 동화 소리 재생 포트 (06에서 Media3로 구현). 대사 한 줄과, 그 위에 겹치는 효과음·배경음 레이어를 재생한다.
 *
 * 오디오 포커스 (기획서 5-6): 내비 안내나 전화로 포커스를 잃으면 어댑터가 **그 자리에서 모든 소리를 멈추고**
 * [focus]로 알린다. 포커스가 돌아와도 스스로 다시 재생하지 않는다. 다시 들을지는 부모가 정하고, 엔진이 [resume]을 부른다.
 *
 * 연출 한 번은 [start] → 재생 → [stop] 순서다. [stop]은 취소된 코루틴의 `finally`에서도 부를 수 있다.
 */
interface AudioPlayerPort {

    val focus: StateFlow<AudioFocus>

    /** 지금 재생 중인 대사의 위치. 대사가 없으면 null. 재생 중에는 짧은 간격으로 갱신된다 */
    val linePosition: StateFlow<LinePosition?>

    /** 포커스를 받는다. 통화 중처럼 받을 수 없으면 false이고, 이때는 연출을 시작하지 않는다 */
    suspend fun start(): Boolean

    /**
     * 대사를 재생하고 **끝날 때까지 기다린다**. 일시정지된 동안에도 계속 기다린다.
     * 코루틴이 취소되면 대사를 멈춘다. 한 번에 한 줄만 재생하고, 앞 대사가 남아 있으면 끊고 새로 시작한다.
     * @throws AudioPlaybackException 파일을 열 수 없거나 재생에 실패했을 때
     */
    suspend fun playLine(clip: AudioClip)

    /**
     * 효과음·배경음을 대사 위에 겹쳐 재생하고 바로 돌아온다.
     * [loop]이면 배경음 자리(한 개)를 바꾸고, 아니면 효과음 자리(한 개)를 바꾼다. [volume]은 0.0~1.0
     */
    suspend fun playSound(clip: AudioClip, loop: Boolean, volume: Float)

    /** 효과음·배경음만 멈춘다 (대사는 그대로) */
    suspend fun stopSounds()

    /** 부모가 누른 일시정지. 모든 레이어를 멈춘다 */
    suspend fun pause()

    /**
     * 멈춘 곳부터 다시 재생한다. 포커스를 잃은 상태면 다시 요청하고, 받지 못하면(통화가 이어지는 중 등) false
     */
    suspend fun resume(): Boolean

    /** 모든 소리를 멈추고 포커스를 돌려준다. 취소된 코루틴에서 불러도 끝까지 실행한다 */
    suspend fun stop()
}

enum class AudioFocus {
    /** 연출 밖 (시작 전, [AudioPlayerPort.stop] 뒤) */
    NONE,

    /** 포커스를 가지고 있다. 일시정지 중일 수도 있다 */
    HELD,

    /** 내비 안내·전화 등으로 잠시 잃었다. 끝나면 [HELD]로 돌아오지만 재생은 멈춘 채로 둔다 */
    LOST_TRANSIENT,

    /** 다른 미디어 앱이 가져갔다. 돌아오지 않으므로 [AudioPlayerPort.resume]이 다시 요청한다 */
    LOST,
}

data class LinePosition(
    val clip: AudioClip,
    val positionMs: Long,
    /** 아직 모르면 null */
    val durationMs: Long?,
)

class AudioPlaybackException(message: String, cause: Throwable? = null) : Exception(message, cause)
