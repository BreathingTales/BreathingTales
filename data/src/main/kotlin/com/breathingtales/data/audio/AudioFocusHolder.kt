package com.breathingtales.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.breathingtales.domain.port.AudioFocus

/**
 * 연출 전체가 함께 쓰는 오디오 포커스 하나. 플레이어(ExoPlayer)마다 포커스를 따로 잡지 않도록
 * ExoPlayer의 포커스 처리는 끄고 여기서만 요청한다.
 *
 * - `setWillPauseWhenDucked(true)`: 내비 안내(MAY_DUCK)가 와도 시스템이 소리만 줄이지 않고
 *   `LOSS_TRANSIENT_CAN_DUCK`을 보내 준다. 우리는 줄이지 않고 멈춘다 (기획서 5-6).
 * - 지연 포커스는 받지 않는다. 통화 중에는 시작하지 않는다.
 */
internal class AudioFocusHolder(
    context: Context,
    /** 메인 스레드에서 불린다 */
    private val onChange: (AudioFocus) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> focusOf(change)?.let(onChange) }, Handler(Looper.getMainLooper()))
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun focusOf(change: Int): AudioFocus? = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocus.HELD
        AudioManager.AUDIOFOCUS_LOSS -> AudioFocus.LOST
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        -> AudioFocus.LOST_TRANSIENT
        else -> null
    }
}
