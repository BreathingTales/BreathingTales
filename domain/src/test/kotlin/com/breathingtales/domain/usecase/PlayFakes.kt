package com.breathingtales.domain.usecase

import com.breathingtales.domain.model.AudioClip
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.port.AudioFocus
import com.breathingtales.domain.port.AudioPlayerPort
import com.breathingtales.domain.port.LinePosition
import com.breathingtales.domain.port.SpeechPort
import com.breathingtales.domain.port.VoiceInputPort
import com.breathingtales.domain.port.VoiceSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** 엔진 테스트의 공통 기록. 항목은 "시각(ms) 내용" */
class EventLog(private val now: () -> Long) {
    val events = mutableListOf<String>()
    fun add(event: String) {
        events += "${now()} $event"
    }
}

/**
 * Media3AudioPlayer처럼 동작하는 가짜: 대사는 [durations]만큼(기본 1초) 걸리고, 멈춘 동안은 흐르지 않는다.
 * 포커스를 잃으면 스스로 멈추고, 돌아와도 [resume] 전까지 멈춘 채로 둔다.
 */
class FakeAudio(private val log: EventLog) : AudioPlayerPort {
    val durations = mutableMapOf<String, Long>()
    var grantFocus = true

    override val focus = MutableStateFlow(AudioFocus.NONE)
    override val linePosition = MutableStateFlow<LinePosition?>(null)
    private val paused = MutableStateFlow(false)

    /** 테스트가 내비 안내·전화를 흉내 낸다 */
    fun loseFocus(transient: Boolean = true) {
        paused.value = true
        focus.value = if (transient) AudioFocus.LOST_TRANSIENT else AudioFocus.LOST
    }

    fun regainFocus() {
        focus.value = AudioFocus.HELD
    }

    override suspend fun start(): Boolean {
        log.add("audio.start")
        if (grantFocus) focus.value = AudioFocus.HELD
        return grantFocus
    }

    override suspend fun playLine(clip: AudioClip) {
        log.add("line ${clip.path}")
        var remaining = durations[clip.path] ?: 1_000L
        while (remaining > 0) {
            paused.first { !it }
            delay(TICK)
            remaining -= TICK
        }
        log.add("line-end ${clip.path}")
    }

    override suspend fun playSound(clip: AudioClip, loop: Boolean, volume: Float) {
        log.add("sound ${clip.path} loop=$loop")
    }

    override suspend fun stopSounds() = log.add("sound-stop")

    override suspend fun pause() {
        log.add("audio.pause")
        paused.value = true
    }

    override suspend fun resume(): Boolean {
        if (focus.value != AudioFocus.HELD) {
            if (!grantFocus) return false
            focus.value = AudioFocus.HELD
        }
        log.add("audio.resume")
        paused.value = false
        return true
    }

    override suspend fun stop() {
        log.add("audio.stop")
        paused.value = false
        focus.value = AudioFocus.NONE
    }

    private companion object {
        const val TICK = 10L
    }
}

/** 문장마다 1초 걸려 읽는다. 끊기면 기록한다 */
class FakeSpeech(private val log: EventLog) : SpeechPort {
    override suspend fun speak(text: String) {
        log.add("tts $text")
        try {
            delay(1_000)
            log.add("tts-end")
        } catch (e: kotlinx.coroutines.CancellationException) {
            log.add("tts-cut")
            throw e
        }
    }
}

class FakeVoice(private val log: EventLog) : VoiceInputPort {
    val signals = MutableSharedFlow<VoiceSignal>()

    /** 마이크 권한 없음처럼 듣기가 바로 실패한다 */
    var failing = false

    override fun listen(type: InteractionType, keywords: List<String>): Flow<VoiceSignal> =
        signals.onStart {
            log.add("mic-on")
            if (failing) error("RECORD_AUDIO 권한 없음")
        }.onCompletion { log.add("mic-off") }
}
