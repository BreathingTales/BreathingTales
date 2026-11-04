package com.breathingtales.data.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.breathingtales.domain.model.AudioClip
import com.breathingtales.domain.port.AudioFocus
import com.breathingtales.domain.port.AudioPlaybackException
import com.breathingtales.domain.port.AudioPlayerPort
import com.breathingtales.domain.port.LinePosition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [AudioPlayerPort]를 Media3로 구현한다. 플레이어 세 개: 대사, 배경음(반복), 효과음(한 번).
 * 음원은 `assets/stories/<storyId>/<path>`에서 읽는다.
 *
 * ExoPlayer는 메인 스레드에서만 다루므로 모든 공개 함수가 메인으로 옮겨 실행한다.
 * 포커스는 [AudioFocusHolder]가 하나만 잡고, 잃으면 [paused]를 켜서 포커스가 돌아와도 스스로 재생하지 않는다.
 */
@Singleton
class Media3AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioPlayerPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _focus = MutableStateFlow(AudioFocus.NONE)
    override val focus: StateFlow<AudioFocus> = _focus.asStateFlow()

    private val _linePosition = MutableStateFlow<LinePosition?>(null)
    override val linePosition: StateFlow<LinePosition?> = _linePosition.asStateFlow()

    private val focusHolder = AudioFocusHolder(context, ::onFocusChange)

    // 아래는 모두 메인 스레드에서만 읽고 쓴다
    private var paused = false
    private var lineClip: AudioClip? = null
    private var lineWaiter: CancellableContinuation<Unit>? = null
    private var positionTicker: Job? = null

    private val linePlayer by lazy {
        newPlayer().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finishLine(null)
                }

                override fun onPlayerError(error: PlaybackException) {
                    finishLine(AudioPlaybackException("대사 재생 실패: $lineClip (${error.errorCodeName})", error))
                }
            })
        }
    }
    private val loopPlayer by lazy { newSoundPlayer("배경음").apply { repeatMode = Player.REPEAT_MODE_ONE } }
    private val effectPlayer by lazy { newSoundPlayer("효과음") }

    private val players get() = listOf(linePlayer, loopPlayer, effectPlayer)

    private val shouldPlay get() = _focus.value == AudioFocus.HELD && !paused

    override suspend fun start(): Boolean = onMain {
        paused = false
        if (!focusHolder.request()) {
            Log.w(TAG, "오디오 포커스를 받지 못해 시작하지 않음")
            return@onMain false
        }
        _focus.value = AudioFocus.HELD
        true
    }

    override suspend fun playLine(clip: AudioClip) = onMain {
        suspendCancellableCoroutine { waiter ->
            lineWaiter?.cancel() // 한 번에 한 줄
            lineWaiter = waiter
            lineClip = clip
            linePlayer.load(clip)
            startPositionTicker()
            waiter.invokeOnCancellation {
                // 아무 스레드에서나 불리므로 메인으로 옮긴다. 그사이 다음 대사가 시작됐으면 건드리지 않는다
                scope.launch { if (lineWaiter === waiter) clearLine() }
            }
        }
    }

    override suspend fun playSound(clip: AudioClip, loop: Boolean, volume: Float) = onMain {
        val player = if (loop) loopPlayer else effectPlayer
        player.volume = volume.coerceIn(0f, 1f)
        player.load(clip)
    }

    override suspend fun stopSounds() = onMain {
        loopPlayer.clear()
        effectPlayer.clear()
    }

    override suspend fun pause() = onMain {
        paused = true
        applyPlayWhenReady()
    }

    override suspend fun resume(): Boolean = onMain {
        when (_focus.value) {
            AudioFocus.NONE -> return@onMain false
            AudioFocus.HELD -> Unit
            AudioFocus.LOST_TRANSIENT, AudioFocus.LOST -> {
                if (!focusHolder.request()) return@onMain false
                _focus.value = AudioFocus.HELD
            }
        }
        paused = false
        applyPlayWhenReady()
        true
    }

    override suspend fun stop() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        lineWaiter?.cancel()
        clearLine()
        loopPlayer.clear()
        effectPlayer.clear()
        if (_focus.value != AudioFocus.NONE) focusHolder.abandon()
        _focus.value = AudioFocus.NONE
        paused = false
    }

    /** 메인 스레드 (포커스 리스너) */
    private fun onFocusChange(focus: AudioFocus) {
        if (_focus.value == AudioFocus.NONE) return // 연출 밖에서 늦게 온 이벤트
        Log.i(TAG, "오디오 포커스: ${_focus.value} → $focus")
        // 잃으면 일시정지로 바꿔 둔다. 다시 받아도 부모가 resume()을 누르기 전까지 멈춘 채로 둔다
        if (focus != AudioFocus.HELD) paused = true
        _focus.value = focus
        applyPlayWhenReady()
    }

    private fun applyPlayWhenReady() {
        val play = shouldPlay
        players.forEach { it.playWhenReady = play }
    }

    private fun finishLine(error: AudioPlaybackException?) {
        val waiter = lineWaiter ?: return
        clearLine()
        if (error == null) waiter.resume(Unit) else waiter.resumeWithException(error)
    }

    private fun clearLine() {
        lineWaiter = null
        lineClip = null
        positionTicker?.cancel()
        positionTicker = null
        _linePosition.value = null
        linePlayer.clear()
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (isActive) {
                val clip = lineClip ?: break
                _linePosition.value = LinePosition(
                    clip = clip,
                    positionMs = linePlayer.currentPosition,
                    durationMs = linePlayer.duration.takeIf { it != C.TIME_UNSET },
                )
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun ExoPlayer.load(clip: AudioClip) {
        setMediaItem(MediaItem.fromUri("asset:///stories/${clip.storyId}/${clip.path}"))
        prepare()
        playWhenReady = shouldPlay
    }

    private fun ExoPlayer.clear() {
        stop()
        clearMediaItems()
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        // 포커스는 AudioFocusHolder가 하나만 잡는다
        .setAudioAttributes(ATTRIBUTES, /* handleAudioFocus = */ false)
        // 오디오만 재생하면 재생 스레드가 약 250ms씩 쉬어 currentPosition도 그 간격으로만 바뀐다.
        // 연출 타이밍에 쓰는 위치라 끈다 (에뮬레이터 측정: 켜면 위치가 실제보다 최대 130ms 늦음)
        .experimentalSetDynamicSchedulingEnabled(false)
        .build()

    /** 효과음·배경음은 꾸밈이라 실패해도 이야기를 멈추지 않고 로그만 남긴다 */
    private fun newSoundPlayer(name: String): ExoPlayer = newPlayer().apply {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "$name 재생 실패: ${currentMediaItem?.localConfiguration?.uri} (${error.errorCodeName})")
            }
        })
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TAG = "BTAudio"
        const val POSITION_INTERVAL_MS = 50L

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
